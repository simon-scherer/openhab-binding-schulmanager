/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.schulmanager.internal.api;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.openhab.binding.schulmanager.internal.api.SchulmanagerException.AuthException;
import org.openhab.binding.schulmanager.internal.api.SchulmanagerException.ModuleUnavailableException;
import org.openhab.binding.schulmanager.internal.api.SchulmanagerException.TwoFactorRequiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Client for one Schulmanager account at one school, using the unofficial JSON API of
 * login.schulmanager-online.de.
 *
 * Login flow (ported from the MIT licensed Home Assistant integration by MrIcemanLE):
 * <ol>
 * <li>POST /api/get-salt</li>
 * <li>PBKDF2-SHA512 of the password with that salt</li>
 * <li>POST /api/login, answers with a JWT, or with requireTOTP/requireTwoFactorEmailCode, or with
 * multipleAccounts for accounts at several schools</li>
 * <li>Accounts with two-factor authentication log in once with the code as "mobile app" and get a userDevice,
 * which afterwards logs in via /api/login-with-user-device without password and code</li>
 * <li>All data via POST /api/calls (batch of module/endpoint requests) with the bearer token</li>
 * </ol>
 *
 * Uses the shared HTTP client of openHAB. The session is the bearer token, no cookies are needed.
 *
 * @author Simon Scherer - Initial contribution
 */
@NonNullByDefault
public class SchulmanagerClient {

    public static final String DEFAULT_BASE_URL = "https://login.schulmanager-online.de";
    private static final String LOGIN_PATH = "/api/login";
    private static final String GET_SALT_PATH = "/api/get-salt";
    private static final String DEVICE_LOGIN_PATH = "/api/login-with-user-device";
    private static final String DELETE_DEVICE_PATH = "/api/delete-user-device";
    private static final String CALLS_PATH = "/api/calls";
    private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String DUMMY_BUNDLE_VERSION = "0000000000";
    private static final long TIMEOUT_SECONDS = 30;
    /** The script bundles read for the bundleVersion are larger than Jetty's default buffer of 2 MB. */
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    /** Status and body of one HTTP exchange. */
    private record Response(int status, String body) {
    }

    private final Logger logger = LoggerFactory.getLogger(SchulmanagerClient.class);
    private final HttpClient http;
    private final String base;
    private final String username;
    private final String password;

    private @Nullable Integer institutionId;
    private @Nullable Integer userId;
    private @Nullable JsonObject userDevice;
    private @Nullable String twoFactorCode;

    private @Nullable String token;
    private @Nullable String bundleVersion;
    private final List<StudentInfo> students = new ArrayList<>();
    private @Nullable JsonArray multipleAccounts;
    private boolean deviceRevoked;
    private final Set<String> unavailableModules = new HashSet<>();

    public SchulmanagerClient(HttpClient http, String username, String password, @Nullable Integer institutionId,
            @Nullable Integer userId, @Nullable JsonObject userDevice, @Nullable String twoFactorCode) {
        this(http, DEFAULT_BASE_URL, username, password, institutionId, userId, userDevice, twoFactorCode);
    }

    /** With another server address, for tests. */
    SchulmanagerClient(HttpClient http, String base, String username, String password,
            @Nullable Integer institutionId, @Nullable Integer userId, @Nullable JsonObject userDevice,
            @Nullable String twoFactorCode) {
        this.http = http;
        this.base = base;
        this.username = username;
        this.password = password;
        this.institutionId = institutionId;
        this.userId = userId;
        this.userDevice = userDevice;
        this.twoFactorCode = twoFactorCode;
    }

    // ------------------------------------------------------------------ getters

    public synchronized boolean hasToken() {
        return token != null;
    }

    public synchronized List<StudentInfo> getStudents() {
        return new ArrayList<>(students);
    }

    public @Nullable Integer getInstitutionId() {
        return institutionId;
    }

    public @Nullable Integer getUserId() {
        return userId;
    }

    public synchronized @Nullable JsonObject getUserDevice() {
        return userDevice;
    }

    public synchronized boolean isDeviceRevoked() {
        return deviceRevoked;
    }

    public synchronized @Nullable JsonArray getMultipleAccounts() {
        return multipleAccounts;
    }

    // ------------------------------------------------------------------ login

    public synchronized void login() throws SchulmanagerException {
        JsonObject device = userDevice;
        if (device != null && loginWithDevice(device)) {
            return;
        }
        loginWithPassword();
    }

    private boolean loginWithDevice(JsonObject device) throws SchulmanagerException {
        JsonObject body = new JsonObject();
        body.add("device", device);
        body.addProperty("reason", "detected-logged-out");
        Response resp = post(base + DEVICE_LOGIN_PATH, body, false);
        if (resp.status() == 401) {
            logger.warn("Schulmanager no longer accepts the remembered device, a new two-factor code is required");
            userDevice = null;
            deviceRevoked = true;
            return false;
        }
        JsonObject data = parseObject(resp.body());
        if (resp.status() != 200 || data == null || str(data, "jwt").isEmpty()) {
            throw new SchulmanagerException("Login with remembered device failed: HTTP " + resp.status());
        }
        applyLoginResponse(data);
        JsonObject renewed = obj(data, "userDevice");
        if (renewed != null) {
            userDevice = renewed;
        }
        return true;
    }

    private void loginWithPassword() throws SchulmanagerException {
        String salt = fetchSalt();
        String hash;
        try {
            hash = Pbkdf2.hashHex(password, salt);
        } catch (GeneralSecurityException e) {
            throw new SchulmanagerException("Password hashing failed", e);
        }
        JsonObject body = new JsonObject();
        body.addProperty("emailOrUsername", username);
        body.addProperty("password", password);
        body.addProperty("hash", hash);
        String code = twoFactorCode;
        // Only a mobile app login returns a device; without a code this would add a device on every login
        body.addProperty("mobileApp", code != null);
        body.add("userId", json(userId));
        body.add("twoFactorCode", code == null ? JsonNull.INSTANCE : new JsonPrimitive(code));
        body.add("institutionId", json(institutionId));

        Response resp = post(base + LOGIN_PATH, body, false);
        if (resp.status() == 401) {
            throw new AuthException("Login rejected (HTTP 401) - check e-mail and password");
        }
        JsonObject data = parseObject(resp.body());
        if (resp.status() != 200 || data == null) {
            throw new SchulmanagerException("Login failed: HTTP " + resp.status());
        }
        JsonElement accounts = data.get("multipleAccounts");
        if (accounts != null && accounts.isJsonArray()) {
            multipleAccounts = accounts.getAsJsonArray();
            logger.debug("Schulmanager account with {} schools", accounts.getAsJsonArray().size());
            return;
        }
        if (isTrue(data, "requireTwoFactorEmailCode") || isTrue(data, "requireTOTP")) {
            String method = isTrue(data, "requireTwoFactorEmailCode") ? "email" : "totp";
            // A code that was given has been used (wrong or expired), do not send it again
            twoFactorCode = null;
            throw new TwoFactorRequiredException(method);
        }
        if (str(data, "jwt").isEmpty()) {
            throw new SchulmanagerException("Login failed: no session received");
        }
        applyLoginResponse(data);
        if (code != null) {
            twoFactorCode = null;
            JsonObject device = obj(data, "userDevice");
            if (device != null) {
                userDevice = device;
                deviceRevoked = false;
            }
        }
    }

    private String fetchSalt() throws SchulmanagerException {
        JsonObject body = new JsonObject();
        body.addProperty("emailOrUsername", username);
        body.add("userId", json(userId));
        body.add("institutionId", json(institutionId));
        Response resp = post(base + GET_SALT_PATH, body, false);
        try {
            JsonElement el = JsonParser.parseString(resp.body());
            if (el.isJsonPrimitive()) {
                return el.getAsString();
            }
        } catch (RuntimeException e) {
            // handled below
        }
        throw new SchulmanagerException("Cannot load the salt: HTTP " + resp.status());
    }

    private void applyLoginResponse(JsonObject data) {
        token = str(data, "jwt");
        JsonObject user = obj(data, "user");
        if (user == null) {
            user = new JsonObject();
        }
        if (userId == null) {
            userId = integer(user, "id");
        }
        if (institutionId == null) {
            institutionId = integer(user, "institutionId");
        }
        students.clear();
        students.addAll(extractStudents(user));
        logger.debug("Schulmanager login successful, {} student(s) found", students.size());
    }

    /**
     * Parent accounts list their children under associatedParents[].student; student accounts carry their own
     * record as associatedStudent or associatedStudents.
     */
    static List<StudentInfo> extractStudents(JsonObject user) {
        List<StudentInfo> result = new ArrayList<>();
        for (JsonElement p : array(user, "associatedParents")) {
            JsonObject student = p.isJsonObject() ? obj(p.getAsJsonObject(), "student") : null;
            if (student != null) {
                addStudent(result, student);
            }
        }
        if (result.isEmpty()) {
            JsonObject own = obj(user, "associatedStudent");
            if (own != null) {
                addStudent(result, own);
            }
            for (JsonElement s : array(user, "associatedStudents")) {
                if (s.isJsonObject()) {
                    addStudent(result, s.getAsJsonObject());
                }
            }
        }
        return result;
    }

    private static void addStudent(List<StudentInfo> list, JsonObject rec) {
        String id = str(rec, "id");
        if (id.isEmpty()) {
            return;
        }
        list.add(new StudentInfo(id, str(rec, "firstname"), str(rec, "lastname"), integer(rec, "classId")));
    }

    /** Remove the remembered device from the Schulmanager account (on thing removal). */
    public synchronized void deleteDevice() throws SchulmanagerException {
        JsonObject device = userDevice;
        if (device == null) {
            return;
        }
        JsonObject body = new JsonObject();
        body.add("device", device);
        Response resp = post(base + DELETE_DEVICE_PATH, body, token != null);
        if (resp.status() != 200) {
            throw new SchulmanagerException("Cannot remove the remembered device: HTTP " + resp.status());
        }
        userDevice = null;
    }

    // ------------------------------------------------------------------ bundle version

    private String discoverBundleVersion() {
        String known = bundleVersion;
        if (known != null) {
            return known;
        }
        String found = DUMMY_BUNDLE_VERSION;
        try {
            String html = get(base + "/");
            List<String> scripts = new ArrayList<>();
            Matcher m = Pattern.compile("<script[^>]+src=[\"']([^\"']+\\.js)[^>]*>", Pattern.CASE_INSENSITIVE)
                    .matcher(html);
            while (m.find()) {
                addScript(scripts, m.group(1));
            }
            m = Pattern.compile("<link[^>]+rel=[\"']modulepreload[\"'][^>]+href=[\"']([^\"']+\\.js)[\"']",
                    Pattern.CASE_INSENSITIVE).matcher(html);
            while (m.find()) {
                addScript(scripts, m.group(1));
            }
            Pattern literal = Pattern.compile("bundleVersion\\s*:\\s*[\"']([a-f0-9]{10})[\"']",
                    Pattern.CASE_INSENSITIVE);
            Pattern near = Pattern.compile("bundleVersion[\\s\\S]{0,120}?[\"']([a-f0-9]{10})[\"']",
                    Pattern.CASE_INSENSITIVE);
            for (String url : scripts) {
                String js = get(url);
                Matcher lm = literal.matcher(js);
                Matcher nm = near.matcher(js);
                String v = lm.find() ? lm.group(1) : nm.find() ? nm.group(1) : null;
                if (v != null) {
                    found = v;
                    break;
                }
            }
        } catch (SchulmanagerException e) {
            logger.debug("bundleVersion not found, using a placeholder: {}", e.getMessage());
        }
        logger.debug("Schulmanager bundleVersion: {}", found);
        bundleVersion = found;
        return found;
    }

    private void addScript(List<String> scripts, @Nullable String src) {
        if (src != null) {
            scripts.add(absolute(src));
        }
    }

    private String absolute(String src) {
        if (src.startsWith("http")) {
            return src;
        }
        return src.startsWith("/") ? base + src : base + "/" + src.replaceFirst("^\\./", "");
    }

    // ------------------------------------------------------------------ /api/calls

    /**
     * Sends a batch of requests and returns the "results" array (one entry per request, each with status and
     * data). Logs in first if needed and retries once with a fresh login on HTTP 401.
     */
    public synchronized JsonArray calls(JsonArray requests) throws SchulmanagerException {
        if (token == null) {
            login();
            if (token == null) {
                throw new SchulmanagerException("No session (multi-school account without school selection?)");
            }
        }
        JsonObject payload = new JsonObject();
        payload.add("requests", requests);
        payload.addProperty("bundleVersion", discoverBundleVersion());
        Response resp = post(base + CALLS_PATH, payload, true);
        if (resp.status() == 401) {
            logger.debug("Schulmanager session expired, logging in again");
            token = null;
            login();
            resp = post(base + CALLS_PATH, payload, true);
        }
        if (logger.isTraceEnabled()) {
            JsonArray names = new JsonArray();
            requests.forEach(r -> names.add(r.isJsonObject() ? str(r.getAsJsonObject(), "endpointName") : ""));
            logger.trace("Schulmanager {} -> HTTP {}: {}", names, resp.status(), resp.body());
        }
        if (resp.status() != 200) {
            throw new SchulmanagerException("/api/calls failed: HTTP " + resp.status());
        }
        JsonObject data = parseObject(resp.body());
        JsonElement results = data == null ? null : data.get("results");
        if (results == null || !results.isJsonArray()) {
            throw new SchulmanagerException("/api/calls: unexpected response");
        }
        return results.getAsJsonArray();
    }

    public static JsonObject request(@Nullable String module, String endpoint, @Nullable JsonObject parameters) {
        JsonObject r = new JsonObject();
        r.add("moduleName", module == null ? JsonNull.INSTANCE : new JsonPrimitive(module));
        r.addProperty("endpointName", endpoint);
        if (parameters != null) {
            r.add("parameters", parameters);
        }
        return r;
    }

    /** Data of one result, or null if that request failed. */
    public static @Nullable JsonElement resultData(JsonArray results, int index) {
        if (results.size() <= index || !results.get(index).isJsonObject()) {
            return null;
        }
        JsonObject r = results.get(index).getAsJsonObject();
        Integer status = integer(r, "status");
        if (status != null && status != 200) {
            return null;
        }
        return r.get("data");
    }

    private @Nullable JsonElement single(@Nullable String module, String endpoint, @Nullable JsonObject params,
            boolean optional) throws SchulmanagerException {
        String moduleName = module == null ? "main" : module;
        if (optional && unavailableModules.contains(moduleName)) {
            throw new ModuleUnavailableException(moduleName);
        }
        JsonArray reqs = new JsonArray();
        reqs.add(request(module, endpoint, params == null ? new JsonObject() : params));
        JsonArray results = calls(reqs);
        if (results.size() > 0 && results.get(0).isJsonObject()) {
            JsonObject r = results.get(0).getAsJsonObject();
            Integer s = integer(r, "status");
            int status = s != null ? s : 200;
            if ((status == 403 || (status == 500 && "get-homework".equals(endpoint))) && optional) {
                unavailableModules.add(moduleName);
                throw new ModuleUnavailableException(moduleName);
            }
            if (status != 200) {
                logger.debug("Schulmanager {}/{} -> Status {}", moduleName, endpoint, status);
                return null;
            }
            return r.get("data");
        }
        return null;
    }

    // ------------------------------------------------------------------ data

    public String fetchInstitutionName() {
        try {
            JsonElement d = single(null, "get-institution", new JsonObject(), false);
            if (d != null && d.isJsonObject()) {
                return str(d.getAsJsonObject(), "name");
            }
        } catch (SchulmanagerException e) {
            logger.debug("School data not available: {}", e.getMessage());
        }
        return "";
    }

    /** Unread parent letters; nothing is opened, so nothing is marked as read. */
    public JsonArray fetchUnreadLetters() throws SchulmanagerException {
        JsonElement d = single("letters", "get-unread-letters-for-widget", new JsonObject(), true);
        return d != null && d.isJsonArray() ? d.getAsJsonArray() : new JsonArray();
    }

    /**
     * All parent letters, newest first (title, date, deadline, read status per student; no body text).
     * Read-only: letters are only marked as read by "letters/confirm", which this binding never calls.
     */
    public JsonArray fetchLetters() throws SchulmanagerException {
        JsonObject p = new JsonObject();
        p.addProperty("offset", 0);
        JsonElement d = single("letters", "get-letters", p, true);
        return d != null && d.isJsonArray() ? d.getAsJsonArray() : new JsonArray();
    }

    /**
     * Body text and attachment list of the given letters, via the read-only ORM gateway (findByPk), in one batch.
     * Returns id -> letter object; letters that could not be read are missing.
     */
    public Map<String, JsonObject> fetchLetterDetails(List<String> ids) throws SchulmanagerException {
        Map<String, JsonObject> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        // The web app opens a letter with letters/get-letter-for-reading {letterId} (captured from the browser).
        // Confirming (marking as read) is the separate call letters/confirm, which this binding never makes.
        JsonArray reqs = new JsonArray();
        for (String id : ids) {
            JsonObject p = new JsonObject();
            try {
                p.addProperty("letterId", Long.parseLong(id));
            } catch (NumberFormatException e) {
                p.addProperty("letterId", id);
            }
            reqs.add(request("letters", "get-letter-for-reading", p));
        }
        JsonArray results = calls(reqs);
        for (int i = 0; i < ids.size(); i++) {
            JsonElement d = resultData(results, i);
            JsonObject letter = null;
            if (d != null && d.isJsonObject()) {
                JsonObject o = d.getAsJsonObject();
                // the letter may come directly or wrapped (e.g. {letter: {...}})
                JsonObject inner = obj(o, "letter");
                letter = o.has("text") || o.has("attachments") || inner == null ? o : inner;
            }
            if (letter != null) {
                result.put(ids.get(i), letter);
            } else if (i == 0) {
                String txt = results.size() > 0 ? results.get(0).toString() : "no answer";
                logger.debug("Letter text (letters/get-letter-for-reading) not readable: {}",
                        txt.substring(0, Math.min(300, txt.length())));
            }
        }
        if (logger.isTraceEnabled() && !result.isEmpty()) {
            String first = result.values().iterator().next().toString();
            logger.trace("Letter (example): {}", first.substring(0, Math.min(1500, first.length())));
        }
        logger.debug("Letter texts loaded: {} of {}", result.size(), ids.size());
        return result;
    }

    /** Number of unread messenger messages (metadata only, no message is opened). */
    public int fetchUnreadMessageCount() throws SchulmanagerException {
        JsonElement d = single("messenger", "count-new-messages", new JsonObject(), true);
        if (d != null && d.isJsonPrimitive() && d.getAsJsonPrimitive().isNumber()) {
            return d.getAsInt();
        }
        JsonObject p = new JsonObject();
        p.addProperty("all", false);
        p.addProperty("includeArchived", false);
        JsonElement subs = single("messenger", "get-subscriptions", p, true);
        int sum = 0;
        if (subs != null && subs.isJsonArray()) {
            for (JsonElement s : subs.getAsJsonArray()) {
                Integer unread = s.isJsonObject() ? integer(s.getAsJsonObject(), "unreadCount") : null;
                if (unread != null) {
                    sum += unread;
                }
            }
        }
        return sum;
    }

    public JsonArray fetchHomework(StudentInfo student) throws SchulmanagerException {
        JsonObject st = new JsonObject();
        st.addProperty("id", Integer.parseInt(student.id));
        JsonObject p = new JsonObject();
        p.add("student", st);
        // answers 500 when the school hides homework from families; then it is not asked again
        JsonElement d = single("classbook", "get-homework", p, true);
        return d != null && d.isJsonArray() ? d.getAsJsonArray() : new JsonArray();
    }

    /**
     * Class exams (exams/get-exams) plus school-wide calendar events visible to the student, in one batch, like the
     * web app does. Returns [exams, exam module events, calendar events, event categories], each may be null.
     */
    public @Nullable JsonElement[] fetchExams(StudentInfo student, LocalDate start, LocalDate end) throws SchulmanagerException {
        JsonObject exams = new JsonObject();
        exams.add("student", studentObject(student, false));
        exams.addProperty("start", start.toString());
        exams.addProperty("end", end.toString());

        JsonObject studentsWhere = new JsonObject();
        studentsWhere.addProperty("id", Integer.parseInt(student.id));
        JsonObject studentsInclude = new JsonObject();
        studentsInclude.addProperty("association", "students");
        studentsInclude.addProperty("required", true);
        studentsInclude.add("attributes", stringArray("id"));
        studentsInclude.add("where", studentsWhere);
        JsonObject groupsInclude = new JsonObject();
        groupsInclude.addProperty("association", "visibleForGroups");
        groupsInclude.addProperty("required", true);
        groupsInclude.add("attributes", stringArray("id"));
        JsonArray inner = new JsonArray();
        inner.add(studentsInclude);
        groupsInclude.add("include", inner);
        JsonObject lte = new JsonObject();
        lte.addProperty("$lte", end.plusDays(1) + "T00:00:00.000Z");
        JsonObject gte = new JsonObject();
        gte.addProperty("$gte", start.minusDays(1) + "T00:00:00.000Z");
        JsonObject where = new JsonObject();
        where.add("start", lte);
        where.add("end", gte);
        JsonObject query = new JsonObject();
        query.add("where", where);
        JsonArray includes = new JsonArray();
        includes.add(groupsInclude);
        query.add("include", includes);
        JsonArray qparams = new JsonArray();
        qparams.add(query);
        JsonObject action = new JsonObject();
        action.addProperty("model", "modules/calendar/event");
        action.addProperty("action", "findAll");
        action.add("parameters", qparams);
        JsonObject poqa = new JsonObject();
        poqa.add("action", action);
        poqa.addProperty("uiState", "main.modules.exams.view");

        // school calendar as shown in the app (incl. location, organizer, category and holidays)
        JsonObject cal = new JsonObject();
        cal.addProperty("start", start.toString());
        cal.addProperty("end", end.toString());
        cal.addProperty("includeHolidays", true);

        JsonArray reqs = new JsonArray();
        reqs.add(request("exams", "get-exams", exams));
        reqs.add(request("exams", "poqa", poqa));
        reqs.add(request("calendar", "get-events-for-user", cal));
        reqs.add(request("calendar", "get-event-categories", new JsonObject()));
        JsonArray results = calls(reqs);
        return new JsonElement[] { resultData(results, 0), resultData(results, 1), resultData(results, 2),
                resultData(results, 3) };
    }

    /**
     * Actual lessons (incl. substitutions and cancellations) between start and end plus the school's class hour
     * times. Returns [lessonsData, classHoursData], either may be null.
     */
    public @Nullable JsonElement[] fetchSchedule(StudentInfo student, LocalDate start, LocalDate end)
            throws SchulmanagerException {
        JsonObject p = new JsonObject();
        p.add("student", studentObject(student, true));
        p.addProperty("start", start.toString());
        p.addProperty("end", end.toString());
        JsonArray reqs = new JsonArray();
        reqs.add(request("schedules", "get-actual-lessons", p));
        reqs.add(request("schedules", "get-class-hours", null));
        JsonArray results = calls(reqs);
        return new JsonElement[] { resultData(results, 0), resultData(results, 1) };
    }

    private static JsonObject studentObject(StudentInfo s, boolean withClass) {
        JsonObject o = new JsonObject();
        o.addProperty("id", Integer.parseInt(s.id));
        o.addProperty("firstname", s.firstname);
        o.addProperty("lastname", s.lastname);
        Integer classId = s.classId;
        if (withClass) {
            o.add("classId", json(classId));
            JsonObject cls = new JsonObject();
            cls.add("id", json(classId));
            cls.add("name", JsonNull.INSTANCE);
            cls.add("gradeLevels", JsonNull.INSTANCE);
            cls.add("isCourseSystem", JsonNull.INSTANCE);
            o.add("class", cls);
        } else {
            o.addProperty("sex", "Male");
            o.addProperty("classId", classId == null ? 0 : classId);
        }
        return o;
    }

    // ------------------------------------------------------------------ http helpers

    private Response post(String url, JsonObject body, boolean auth) throws SchulmanagerException {
        Request req = http.newRequest(url).method(HttpMethod.POST).timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .agent(USER_AGENT).header(HttpHeader.ACCEPT, "application/json, text/plain, */*")
                .header(HttpHeader.ACCEPT_LANGUAGE, "de-DE,de;q=0.9").content(new StringContentProvider(
                        "application/json;charset=UTF-8", body.toString(), StandardCharsets.UTF_8));
        String t = token;
        if (auth && t != null) {
            req.header(HttpHeader.AUTHORIZATION, "Bearer " + t);
        }
        return send(req);
    }

    private String get(String url) throws SchulmanagerException {
        Request req = http.newRequest(url).method(HttpMethod.GET).timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .agent(USER_AGENT);
        Response resp = send(req);
        if (resp.status() != 200) {
            throw new SchulmanagerException("GET " + url + " -> HTTP " + resp.status());
        }
        return resp.body();
    }

    private Response send(Request req) throws SchulmanagerException {
        FutureResponseListener listener = new FutureResponseListener(req, MAX_RESPONSE_BYTES);
        req.send(listener);
        try {
            ContentResponse resp = listener.get(TIMEOUT_SECONDS + 5, TimeUnit.SECONDS);
            // Schulmanager answers in UTF-8, also where the content type names no charset
            return new Response(resp.getStatus(), new String(resp.getContent(), StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            req.abort(e);
            Thread.currentThread().interrupt();
            throw new SchulmanagerException("Interrupted", e);
        } catch (TimeoutException e) {
            req.abort(e);
            throw new SchulmanagerException("Timeout: " + req.getURI(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpResponseException hre) {
                // Jetty rejects a 401 without WWW-Authenticate header (as Schulmanager sends it) as protocol
                // violation; the status is still needed to detect wrong credentials and expired sessions
                return new Response(hre.getResponse().getStatus(), "");
            }
            throw new SchulmanagerException("Connection error: " + (cause != null ? cause.getMessage() : e.getMessage()),
                    e);
        }
    }

    // ------------------------------------------------------------------ json helpers

    private static @Nullable JsonObject parseObject(String text) {
        try {
            JsonElement el = JsonParser.parseString(text);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isTrue(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static @Nullable JsonObject obj(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static @Nullable Integer integer(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        try {
            return e.getAsInt();
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static JsonElement json(@Nullable Integer value) {
        return value == null ? JsonNull.INSTANCE : new JsonPrimitive(value);
    }

    private static JsonArray array(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    private static JsonArray stringArray(String... values) {
        JsonArray a = new JsonArray();
        for (String v : values) {
            a.add(v);
        }
        return a;
    }
}
