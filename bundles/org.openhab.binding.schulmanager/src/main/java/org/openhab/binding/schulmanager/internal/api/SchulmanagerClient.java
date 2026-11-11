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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * @author Simon Scherer - Initial contribution
 */
public class SchulmanagerClient {

    private static final String BASE = "https://login.schulmanager-online.de";
    private static final String LOGIN_URL = BASE + "/api/login";
    private static final String GET_SALT_URL = BASE + "/api/get-salt";
    private static final String DEVICE_LOGIN_URL = BASE + "/api/login-with-user-device";
    private static final String DELETE_DEVICE_URL = BASE + "/api/delete-user-device";
    private static final String CALLS_URL = BASE + "/api/calls";
    private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String DUMMY_BUNDLE_VERSION = "0000000000";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final Logger logger = LoggerFactory.getLogger(SchulmanagerClient.class);
    private final HttpClient http;
    private final String username;
    private final String password;

    private Integer institutionId;
    private Integer userId;
    private JsonObject userDevice;
    private String twoFactorCode;

    private String token;
    private String bundleVersion;
    private final List<StudentInfo> students = new ArrayList<>();
    private JsonArray multipleAccounts;
    private boolean deviceRevoked;
    private final Set<String> unavailableModules = new HashSet<>();

    public SchulmanagerClient(HttpClient http, String username, String password, Integer institutionId, Integer userId,
            JsonObject userDevice, String twoFactorCode) {
        this.http = http;
        this.username = username;
        this.password = password;
        this.institutionId = institutionId;
        this.userId = userId;
        this.userDevice = userDevice;
        this.twoFactorCode = twoFactorCode;
    }

    public static HttpClient createHttpClient() {
        return HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    // ------------------------------------------------------------------ getters

    public synchronized boolean hasToken() {
        return token != null;
    }

    public synchronized List<StudentInfo> getStudents() {
        return new ArrayList<>(students);
    }

    public Integer getInstitutionId() {
        return institutionId;
    }

    public Integer getUserId() {
        return userId;
    }

    public synchronized JsonObject getUserDevice() {
        return userDevice;
    }

    public synchronized boolean isDeviceRevoked() {
        return deviceRevoked;
    }

    public synchronized JsonArray getMultipleAccounts() {
        return multipleAccounts;
    }

    // ------------------------------------------------------------------ login

    public synchronized void login() throws SchulmanagerException {
        if (userDevice != null && loginWithDevice()) {
            return;
        }
        loginWithPassword();
    }

    private boolean loginWithDevice() throws SchulmanagerException {
        JsonObject body = new JsonObject();
        body.add("device", userDevice);
        body.addProperty("reason", "detected-logged-out");
        HttpResponse<String> resp = post(DEVICE_LOGIN_URL, body, false);
        if (resp.statusCode() == 401) {
            logger.warn("Schulmanager no longer accepts the remembered device, a new two-factor code is required");
            userDevice = null;
            deviceRevoked = true;
            return false;
        }
        JsonObject data = parseObject(resp.body());
        if (resp.statusCode() != 200 || data == null || !data.has("jwt")) {
            throw new SchulmanagerException("Login with remembered device failed: HTTP " + resp.statusCode());
        }
        applyLoginResponse(data);
        if (data.has("userDevice") && data.get("userDevice").isJsonObject()) {
            userDevice = data.getAsJsonObject("userDevice");
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
        // Only a mobile app login returns a device; without a code this would add a device on every login
        body.addProperty("mobileApp", twoFactorCode != null);
        body.add("userId", userId == null ? JsonNull.INSTANCE : new JsonPrimitive(userId));
        body.add("twoFactorCode", twoFactorCode == null ? JsonNull.INSTANCE : new JsonPrimitive(twoFactorCode));
        body.add("institutionId", institutionId == null ? JsonNull.INSTANCE : new JsonPrimitive(institutionId));

        HttpResponse<String> resp = post(LOGIN_URL, body, false);
        if (resp.statusCode() == 401) {
            throw new AuthException("Login rejected (HTTP 401) - check e-mail and password");
        }
        JsonObject data = parseObject(resp.body());
        if (resp.statusCode() != 200 || data == null) {
            throw new SchulmanagerException("Login failed: HTTP " + resp.statusCode());
        }
        if (data.has("multipleAccounts") && data.get("multipleAccounts").isJsonArray()) {
            multipleAccounts = data.getAsJsonArray("multipleAccounts");
            logger.debug("Schulmanager account with {} schools", multipleAccounts.size());
            return;
        }
        if (isTrue(data, "requireTwoFactorEmailCode") || isTrue(data, "requireTOTP")) {
            String method = isTrue(data, "requireTwoFactorEmailCode") ? "email" : "totp";
            // A code that was given has been used (wrong or expired), do not send it again
            twoFactorCode = null;
            throw new TwoFactorRequiredException(method);
        }
        if (!data.has("jwt")) {
            throw new SchulmanagerException("Login failed: no session received");
        }
        applyLoginResponse(data);
        if (twoFactorCode != null) {
            twoFactorCode = null;
            if (data.has("userDevice") && data.get("userDevice").isJsonObject()) {
                userDevice = data.getAsJsonObject("userDevice");
                deviceRevoked = false;
            }
        }
    }

    private String fetchSalt() throws SchulmanagerException {
        JsonObject body = new JsonObject();
        body.addProperty("emailOrUsername", username);
        body.add("userId", userId == null ? JsonNull.INSTANCE : new JsonPrimitive(userId));
        body.add("institutionId", institutionId == null ? JsonNull.INSTANCE : new JsonPrimitive(institutionId));
        HttpResponse<String> resp = post(GET_SALT_URL, body, false);
        try {
            JsonElement el = JsonParser.parseString(resp.body());
            if (el.isJsonPrimitive()) {
                return el.getAsString();
            }
        } catch (RuntimeException e) {
            // handled below
        }
        throw new SchulmanagerException("Cannot load the salt: HTTP " + resp.statusCode());
    }

    private void applyLoginResponse(JsonObject data) {
        token = data.get("jwt").getAsString();
        JsonObject user = data.has("user") && data.get("user").isJsonObject() ? data.getAsJsonObject("user")
                : new JsonObject();
        if (userId == null && user.has("id") && !user.get("id").isJsonNull()) {
            userId = user.get("id").getAsInt();
        }
        if (institutionId == null && user.has("institutionId") && !user.get("institutionId").isJsonNull()) {
            institutionId = user.get("institutionId").getAsInt();
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
            if (p.isJsonObject() && p.getAsJsonObject().has("student")
                    && p.getAsJsonObject().get("student").isJsonObject()) {
                addStudent(result, p.getAsJsonObject().getAsJsonObject("student"));
            }
        }
        if (result.isEmpty()) {
            if (user.has("associatedStudent") && user.get("associatedStudent").isJsonObject()) {
                addStudent(result, user.getAsJsonObject("associatedStudent"));
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
        if (!rec.has("id") || rec.get("id").isJsonNull()) {
            return;
        }
        Integer classId = rec.has("classId") && !rec.get("classId").isJsonNull() ? rec.get("classId").getAsInt() : null;
        list.add(new StudentInfo(rec.get("id").getAsString(), str(rec, "firstname"), str(rec, "lastname"), classId));
    }

    /** Remove the remembered device from the Schulmanager account (on thing removal). */
    public synchronized void deleteDevice() throws SchulmanagerException {
        if (userDevice == null) {
            return;
        }
        JsonObject body = new JsonObject();
        body.add("device", userDevice);
        HttpResponse<String> resp = post(DELETE_DEVICE_URL, body, token != null);
        if (resp.statusCode() != 200) {
            throw new SchulmanagerException("Cannot remove the remembered device: HTTP " + resp.statusCode());
        }
        userDevice = null;
    }

    // ------------------------------------------------------------------ bundle version

    private String discoverBundleVersion() {
        if (bundleVersion != null) {
            return bundleVersion;
        }
        bundleVersion = DUMMY_BUNDLE_VERSION;
        try {
            String html = get(BASE + "/");
            List<String> scripts = new ArrayList<>();
            Matcher m = Pattern.compile("<script[^>]+src=[\"']([^\"']+\\.js)[^>]*>", Pattern.CASE_INSENSITIVE)
                    .matcher(html);
            while (m.find()) {
                scripts.add(absolute(m.group(1)));
            }
            m = Pattern.compile("<link[^>]+rel=[\"']modulepreload[\"'][^>]+href=[\"']([^\"']+\\.js)[\"']",
                    Pattern.CASE_INSENSITIVE).matcher(html);
            while (m.find()) {
                scripts.add(absolute(m.group(1)));
            }
            Pattern literal = Pattern.compile("bundleVersion\\s*:\\s*[\"']([a-f0-9]{10})[\"']",
                    Pattern.CASE_INSENSITIVE);
            Pattern near = Pattern.compile("bundleVersion[\\s\\S]{0,120}?[\"']([a-f0-9]{10})[\"']",
                    Pattern.CASE_INSENSITIVE);
            for (String url : scripts) {
                String js = get(url);
                Matcher lm = literal.matcher(js);
                if (lm.find()) {
                    bundleVersion = lm.group(1);
                    break;
                }
                Matcher nm = near.matcher(js);
                if (nm.find()) {
                    bundleVersion = nm.group(1);
                    break;
                }
            }
        } catch (SchulmanagerException e) {
            logger.debug("bundleVersion not found, using a placeholder: {}", e.getMessage());
        }
        logger.debug("Schulmanager bundleVersion: {}", bundleVersion);
        return bundleVersion;
    }

    private static String absolute(String src) {
        if (src.startsWith("http")) {
            return src;
        }
        return src.startsWith("/") ? BASE + src : BASE + "/" + src.replaceFirst("^\\./", "");
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
        HttpResponse<String> resp = post(CALLS_URL, payload, true);
        if (resp.statusCode() == 401) {
            logger.debug("Schulmanager session expired, logging in again");
            token = null;
            login();
            resp = post(CALLS_URL, payload, true);
        }
        if (logger.isTraceEnabled()) {
            JsonArray names = new JsonArray();
            requests.forEach(r -> names.add(r.getAsJsonObject().get("endpointName")));
            logger.trace("Schulmanager {} -> HTTP {}: {}", names, resp.statusCode(), resp.body());
        }
        if (resp.statusCode() != 200) {
            throw new SchulmanagerException("/api/calls failed: HTTP " + resp.statusCode());
        }
        JsonObject data = parseObject(resp.body());
        if (data == null || !data.has("results") || !data.get("results").isJsonArray()) {
            throw new SchulmanagerException("/api/calls: unexpected response");
        }
        return data.getAsJsonArray("results");
    }

    public static JsonObject request(String module, String endpoint, JsonObject parameters) {
        JsonObject r = new JsonObject();
        r.add("moduleName", module == null ? JsonNull.INSTANCE : new JsonPrimitive(module));
        r.addProperty("endpointName", endpoint);
        if (parameters != null) {
            r.add("parameters", parameters);
        }
        return r;
    }

    /** Data of one result, or null if that request failed. */
    public static JsonElement resultData(JsonArray results, int index) {
        if (results.size() <= index || !results.get(index).isJsonObject()) {
            return null;
        }
        JsonObject r = results.get(index).getAsJsonObject();
        if (r.has("status") && r.get("status").getAsInt() != 200) {
            return null;
        }
        return r.get("data");
    }

    private JsonElement single(String module, String endpoint, JsonObject params, boolean optional)
            throws SchulmanagerException {
        if (optional && unavailableModules.contains(module)) {
            throw new ModuleUnavailableException(module);
        }
        JsonArray reqs = new JsonArray();
        reqs.add(request(module, endpoint, params == null ? new JsonObject() : params));
        JsonArray results = calls(reqs);
        if (results.size() > 0 && results.get(0).isJsonObject()) {
            JsonObject r = results.get(0).getAsJsonObject();
            int status = r.has("status") ? r.get("status").getAsInt() : 200;
            if ((status == 403 || (status == 500 && "get-homework".equals(endpoint))) && optional) {
                unavailableModules.add(module);
                throw new ModuleUnavailableException(module);
            }
            if (status != 200) {
                logger.debug("Schulmanager {}/{} -> Status {}", module, endpoint, status);
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
                letter = o.has("text") || o.has("attachments") ? o
                        : o.has("letter") && o.get("letter").isJsonObject() ? o.getAsJsonObject("letter") : o;
            }
            if (letter != null) {
                result.put(ids.get(i), letter);
            } else if (i == 0) {
                String txt = results.size() > 0 ? results.get(0).toString() : "keine Antwort";
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
                if (s.isJsonObject() && s.getAsJsonObject().has("unreadCount")
                        && !s.getAsJsonObject().get("unreadCount").isJsonNull()) {
                    sum += s.getAsJsonObject().get("unreadCount").getAsInt();
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
     * web app does. Returns [examsData, calendarEventsData], either may be null.
     */
    public JsonElement[] fetchExams(StudentInfo student, LocalDate start, LocalDate end) throws SchulmanagerException {
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
    public JsonElement[] fetchSchedule(StudentInfo student, LocalDate start, LocalDate end)
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
        if (withClass) {
            o.add("classId", s.classId == null ? JsonNull.INSTANCE : new JsonPrimitive(s.classId));
            JsonObject cls = new JsonObject();
            cls.add("id", s.classId == null ? JsonNull.INSTANCE : new JsonPrimitive(s.classId));
            cls.add("name", JsonNull.INSTANCE);
            cls.add("gradeLevels", JsonNull.INSTANCE);
            cls.add("isCourseSystem", JsonNull.INSTANCE);
            o.add("class", cls);
        } else {
            o.addProperty("sex", "Male");
            o.addProperty("classId", s.classId == null ? 0 : s.classId);
        }
        return o;
    }

    // ------------------------------------------------------------------ http helpers

    private HttpResponse<String> post(String url, JsonObject body, boolean auth) throws SchulmanagerException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("User-Agent", USER_AGENT).header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "de-DE,de;q=0.9").header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (auth && token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return send(b.build());
    }

    private String get(String url) throws SchulmanagerException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).header("User-Agent", USER_AGENT)
                .GET().build();
        HttpResponse<String> resp = send(req);
        if (resp.statusCode() != 200) {
            throw new SchulmanagerException("GET " + url + " -> HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    private HttpResponse<String> send(HttpRequest req) throws SchulmanagerException {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SchulmanagerException("Connection error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SchulmanagerException("Interrupted", e);
        }
    }

    // ------------------------------------------------------------------ json helpers

    private static JsonObject parseObject(String text) {
        try {
            JsonElement el = JsonParser.parseString(text);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isTrue(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
    }

    static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static JsonArray array(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static JsonArray stringArray(String... values) {
        JsonArray a = new JsonArray();
        for (String v : values) {
            a.add(v);
        }
        return a;
    }
}
