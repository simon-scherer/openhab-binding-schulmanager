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
package org.openhab.binding.schulmanager.internal.handler;

import static org.openhab.binding.schulmanager.internal.SchulmanagerBindingConstants.*;

import java.net.http.HttpClient;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.schulmanager.internal.api.SchulmanagerClient;
import org.openhab.binding.schulmanager.internal.api.SchulmanagerException;
import org.openhab.binding.schulmanager.internal.api.SchulmanagerException.AuthException;
import org.openhab.binding.schulmanager.internal.api.SchulmanagerException.ModuleUnavailableException;
import org.openhab.binding.schulmanager.internal.api.SchulmanagerException.TwoFactorRequiredException;
import org.openhab.binding.schulmanager.internal.api.StudentInfo;
import org.openhab.binding.schulmanager.internal.config.AccountConfiguration;
import org.openhab.binding.schulmanager.internal.model.DataParser;
import org.openhab.binding.schulmanager.internal.model.DataParser.Schedule;
import org.openhab.binding.schulmanager.internal.model.Exam;
import org.openhab.binding.schulmanager.internal.model.Homework;
import org.openhab.binding.schulmanager.internal.model.Letters;
import org.openhab.binding.schulmanager.internal.model.SubjectNames;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Bridge for one Schulmanager Online login (e-mail + password). Handles single- and multi-school accounts and
 * two-factor authentication, polls the data of all student things and the account-wide counters.
 *
 * @author Simon Scherer - Initial contribution
 */
@NonNullByDefault
public class SchulmanagerAccountHandler extends BaseBridgeHandler {

    private static final long MIN_MANUAL_REFRESH_GAP_MS = 2 * 60 * 1000L;

    private final Logger logger = LoggerFactory.getLogger(SchulmanagerAccountHandler.class);
    private final HttpClient http;
    private final Supplier<ZoneId> zone;

    private AccountConfiguration config = new AccountConfiguration();
    private SubjectNames subjectNames = SubjectNames.NONE;
    private @Nullable DeviceStore deviceStore;
    private final List<JsonObject> devices = new ArrayList<>();
    /** One client per school account. */
    private final List<SchulmanagerClient> clients = new ArrayList<>();
    /** studentId -> client of the school the student belongs to */
    private final Map<String, SchulmanagerClient> clientByStudent = new LinkedHashMap<>();
    private final Map<String, StudentInfo> students = new LinkedHashMap<>();

    /** id -> detail of letters already loaded (the text does not change once sent) */
    private final Map<String, JsonObject> letterDetails = new ConcurrentHashMap<>();
    private @Nullable String lastLettersJson;

    private @Nullable ScheduledFuture<?> pollJob;
    private @Nullable ScheduledFuture<?> connectJob;
    private volatile long lastRefresh;

    public SchulmanagerAccountHandler(Bridge bridge, HttpClient http, Supplier<ZoneId> zone) {
        super(bridge);
        this.http = http;
        this.zone = zone;
    }

    // ================================================================== lifecycle

    @Override
    public void initialize() {
        AccountConfiguration config = getConfigAs(AccountConfiguration.class);
        if (config.username.isBlank() || config.password.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error.credentials-missing");
            return;
        }
        config.refreshInterval = Math.max(15, config.refreshInterval);
        config.scheduleWeeks = Math.min(3, Math.max(1, config.scheduleWeeks));
        config.examDaysAhead = Math.min(365, Math.max(7, config.examDaysAhead));
        this.config = config;
        subjectNames = SubjectNames.parse(config.subjectNames);
        deviceStore = new DeviceStore(getThing().getUID().getAsString());
        updateStatus(ThingStatus.UNKNOWN);
        connectJob = scheduler.schedule(this::connectAndStartPolling, 0, TimeUnit.SECONDS);
    }

    @Override
    public void dispose() {
        cancel(connectJob);
        cancel(pollJob);
        connectJob = null;
        pollJob = null;
        synchronized (this) {
            clients.clear();
            clientByStudent.clear();
            students.clear();
        }
        super.dispose();
    }

    @Override
    public void handleRemoval() {
        // remove the remembered device from the Schulmanager account, like the app does on logout
        scheduler.execute(() -> {
            for (SchulmanagerClient c : new ArrayList<>(clients)) {
                try {
                    c.deleteDevice();
                } catch (SchulmanagerException e) {
                    logger.debug("Remembered device not removed: {}", e.getMessage());
                }
            }
            DeviceStore store = deviceStore;
            if (store != null) {
                store.delete();
            }
            updateStatus(ThingStatus.REMOVED);
        });
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            requestRefresh();
        }
    }

    @Override
    public void childHandlerInitialized(ThingHandler childHandler, Thing childThing) {
        if (childHandler instanceof SchulmanagerStudentHandler student
                && getThing().getStatus() == ThingStatus.ONLINE) {
            scheduler.execute(() -> refreshStudent(student));
        }
    }

    private static void cancel(@Nullable ScheduledFuture<?> job) {
        if (job != null) {
            job.cancel(true);
        }
    }

    // ================================================================== connect

    private void connectAndStartPolling() {
        try {
            connect();
        } catch (TwoFactorRequiredException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "email".equals(e.getMethod()) ? "@text/offline.conf-error.two-factor-email"
                            : "@text/offline.conf-error.two-factor-totp");
            return;
        } catch (AuthException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, e.getMessage());
            return;
        } catch (SchulmanagerException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            // retry later
            connectJob = scheduler.schedule(this::connectAndStartPolling, 5, TimeUnit.MINUTES);
            return;
        }
        updateStatus(ThingStatus.ONLINE);
        cancel(pollJob);
        pollJob = scheduler.scheduleWithFixedDelay(this::poll, 2, config.refreshInterval * 60L, TimeUnit.SECONDS);
    }

    private synchronized void connect() throws SchulmanagerException {
        clients.clear();
        clientByStudent.clear();
        students.clear();
        devices.clear();
        DeviceStore store = deviceStore;
        if (store != null) {
            devices.addAll(store.load());
        }

        String code = config.twoFactorCode.replaceAll("\\s", "");
        JsonObject probeDevice = devices.size() == 1 ? devices.get(0) : null;
        // The code is only used while no device is remembered; an old code left in the config is ignored
        SchulmanagerClient probe = new SchulmanagerClient(http, config.username, config.password, null, null,
                probeDevice, probeDevice == null && !code.isEmpty() ? code : null);
        try {
            probe.login();
        } finally {
            syncDevice(probeDevice, probe);
        }

        if (probe.hasToken()) {
            clients.add(probe);
        } else {
            JsonArray accounts = probe.getMultipleAccounts();
            if (accounts == null || accounts.isEmpty()) {
                throw new SchulmanagerException("Login returned neither a session nor a list of schools");
            }
            SchulmanagerException firstError = null;
            for (JsonElement a : accounts) {
                if (!a.isJsonObject()) {
                    continue;
                }
                JsonObject acc = a.getAsJsonObject();
                // the "id" of a multipleAccounts record is the user id of that school's account
                Integer uid = acc.has("id") && !acc.get("id").isJsonNull() ? acc.get("id").getAsInt() : null;
                JsonObject dev = deviceFor(uid);
                SchulmanagerClient c = new SchulmanagerClient(http, config.username, config.password, null, uid, dev,
                        dev == null && !code.isEmpty() ? code : null);
                try {
                    c.login();
                    if (c.hasToken()) {
                        clients.add(c);
                    }
                } catch (TwoFactorRequiredException e) {
                    throw e;
                } catch (SchulmanagerException e) {
                    logger.warn("Login to school '{}' failed: {}",
                            acc.has("label") ? acc.get("label").getAsString() : uid, e.getMessage());
                    if (firstError == null) {
                        firstError = e;
                    }
                } finally {
                    syncDevice(dev, c);
                }
            }
            if (clients.isEmpty()) {
                throw firstError != null ? firstError : new SchulmanagerException("No school reachable");
            }
        }
        if (store != null) {
            store.save(devices);
        }

        Map<String, String> props = new LinkedHashMap<>();
        StringBuilder overview = new StringBuilder();
        for (SchulmanagerClient c : clients) {
            String school = c.fetchInstitutionName();
            for (StudentInfo s : c.getStudents()) {
                s.schoolName = school;
                students.put(s.id, s);
                clientByStudent.put(s.id, c);
                if (overview.length() > 0) {
                    overview.append("; ");
                }
                overview.append(s.id).append(" = ").append(s.getName());
                if (!school.isEmpty()) {
                    overview.append(" (").append(school).append(")");
                }
            }
        }
        props.put(PROPERTY_STUDENTS, overview.length() == 0 ? "-" : overview.toString());
        props.put(PROPERTY_SCHOOLS, String.valueOf(clients.size()));
        props.put(PROPERTY_TWO_FACTOR_DEVICE, String.valueOf(!devices.isEmpty()));
        updateProperties(props);
        logger.debug("Schulmanager connected: {}", overview);
    }

    private @Nullable JsonObject deviceFor(@Nullable Integer userId) {
        for (JsonObject d : devices) {
            if (userId != null && d.has("userId") && !d.get("userId").isJsonNull()
                    && d.get("userId").getAsInt() == userId) {
                return d;
            }
        }
        return null;
    }

    private void syncDevice(@Nullable JsonObject previous, SchulmanagerClient client) {
        JsonObject current = client.getUserDevice();
        if (Objects.equals(current, previous)) {
            return;
        }
        devices.removeIf(d -> d.equals(previous) || (current != null && d.has("userId") && current.has("userId")
                && d.get("userId").equals(current.get("userId"))));
        if (current != null) {
            devices.add(current);
            logger.info("Device for the two-factor login remembered, the code is no longer needed");
        }
        DeviceStore store = deviceStore;
        if (store != null) {
            store.save(devices);
        }
    }

    // ================================================================== polling

    /** Manual refresh (REFRESH command), limited to one every two minutes. */
    public void requestRefresh() {
        if (System.currentTimeMillis() - lastRefresh < MIN_MANUAL_REFRESH_GAP_MS) {
            logger.debug("Refresh skipped (too soon after the last one)");
            return;
        }
        scheduler.execute(this::poll);
    }

    private void poll() {
        lastRefresh = System.currentTimeMillis();
        boolean anyOk = false;
        String lastError = null;
        for (Thing t : getThing().getThings()) {
            if (t.getHandler() instanceof SchulmanagerStudentHandler student) {
                String err = refreshStudent(student);
                if (err == null) {
                    anyOk = true;
                } else {
                    lastError = err;
                }
            }
        }
        String err = refreshAccountCounters();
        if (err == null) {
            anyOk = true;
        } else {
            lastError = err;
        }
        if (anyOk) {
            updateStatus(ThingStatus.ONLINE);
            updateState(CH_LAST_UPDATE, new DateTimeType(ZonedDateTime.now(zone.get())));
        } else if (lastError != null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, lastError);
        }
    }

    private @Nullable String refreshAccountCounters() {
        int letters = 0;
        int messages = 0;
        List<String> letterTitles = new ArrayList<>();
        boolean lettersAvailable = false;
        boolean messagesAvailable = false;
        String error = null;
        for (SchulmanagerClient c : new ArrayList<>(clients)) {
            try {
                JsonArray l = c.fetchUnreadLetters();
                lettersAvailable = true;
                letters += l.size();
                for (JsonElement e : l) {
                    if (e.isJsonObject() && e.getAsJsonObject().has("title")) {
                        letterTitles.add(e.getAsJsonObject().get("title").getAsString());
                    }
                }
            } catch (ModuleUnavailableException e) {
                // school has no parent letters module
            } catch (SchulmanagerException e) {
                error = e.getMessage();
            }
            try {
                messages += c.fetchUnreadMessageCount();
                messagesAvailable = true;
            } catch (ModuleUnavailableException e) {
                // no messenger
            } catch (SchulmanagerException e) {
                error = e.getMessage();
            }
        }
        if (lettersAvailable) {
            updateState(CH_LETTERS_UNREAD, new DecimalType(letters));
            updateState(CH_LETTERS_LIST, new StringType(String.join("\n", letterTitles)));
        }
        if (messagesAvailable) {
            updateState(CH_MESSAGES_UNREAD, new DecimalType(messages));
        }
        if (lettersAvailable && isLinked(CH_LETTERS_WIDGET)) {
            String e = refreshLetterWidget();
            if (e != null) {
                error = e;
            }
        } else {
            lastLettersJson = null; // publish again as soon as the channel gets linked
        }
        return error;
    }

    private @Nullable String refreshLetterWidget() {
        ZoneId z = zone.get();
        List<Letters.Letter> all = new ArrayList<>();
        Map<String, SchulmanagerClient> clientOfLetter = new HashMap<>();
        String error = null;
        for (SchulmanagerClient c : new ArrayList<>(clients)) {
            try {
                for (Letters.Letter l : Letters.parseList(c.fetchLetters(), z)) {
                    all.add(l);
                    clientOfLetter.put(l.id, c);
                }
            } catch (ModuleUnavailableException e) {
                // no letters module
            } catch (SchulmanagerException e) {
                error = "Parent letters: " + e.getMessage();
            }
        }
        ZonedDateTime veryOld = ZonedDateTime.now(z).minusYears(50);
        all.sort(Comparator.comparing((Letters.Letter l) -> {
            ZonedDateTime sent = l.sent;
            return sent == null ? veryOld : sent;
        }).reversed());
        List<Letters.Letter> shown = all.subList(0, Math.min(Letters.MAX_LETTERS, all.size()));
        // load text and attachments only for letters not loaded before (per school, in one batch each).
        // Unread letters are skipped unless configured: it is not verified that loading leaves them unread.
        Map<SchulmanagerClient, List<String>> missing = new HashMap<>();
        for (Letters.Letter l : shown) {
            SchulmanagerClient c = clientOfLetter.get(l.id);
            if (c != null && !letterDetails.containsKey(l.id) && (!l.unread || config.lettersLoadUnread)) {
                missing.computeIfAbsent(c, k -> new ArrayList<>()).add(l.id);
            }
        }
        for (Map.Entry<SchulmanagerClient, List<String>> m : missing.entrySet()) {
            try {
                letterDetails.putAll(m.getKey().fetchLetterDetails(m.getValue()));
            } catch (SchulmanagerException e) {
                error = "Parent letters (text): " + e.getMessage();
            }
        }
        Map<String, String> names = new HashMap<>();
        synchronized (this) {
            for (StudentInfo s : students.values()) {
                names.put(s.id, s.firstname.isEmpty() ? s.getName() : s.firstname);
            }
        }
        String json = Letters.widget(shown, letterDetails, names, LocalDate.now(z)).toString();
        if (!json.equals(lastLettersJson)) {
            lastLettersJson = json;
            updateState(CH_LETTERS_WIDGET, new StringType(json));
        }
        return error;
    }

    /**
     * Fetches timetable, exams and homework of one student and hands them to its handler.
     *
     * @return an error text if nothing at all could be loaded, otherwise null
     */
    @Nullable
    String refreshStudent(SchulmanagerStudentHandler handler) {
        String studentId = handler.getStudentId();
        SchulmanagerClient client;
        StudentInfo info;
        String known;
        synchronized (this) {
            client = clientByStudent.get(studentId);
            info = students.get(studentId);
            known = String.join(", ", students.keySet());
        }
        if (client == null || info == null) {
            handler.setUnknownStudent(known);
            return null;
        }
        LocalDate today = LocalDate.now(zone.get());
        String error = null;

        Schedule schedule = null;
        try {
            LocalDate start = today.minusDays(today.getDayOfWeek().getValue() - 1L);
            int weeks = config.scheduleWeeks + (today.getDayOfWeek().getValue() >= 6 ? 1 : 0);
            LocalDate end = start.plusDays(7L * weeks - 1);
            JsonElement[] r = client.fetchSchedule(info, start, end);
            if (r[0] != null) {
                schedule = DataParser.parseSchedule(r[0], r[1], start, end, config.teacherDisplay,
                        config.subjectDisplay, subjectNames);
            } else {
                error = "Timetable not available";
            }
        } catch (SchulmanagerException e) {
            error = "Timetable: " + e.getMessage();
        }

        List<Exam> exams = null;
        try {
            JsonElement[] r = client.fetchExams(info, today.minusDays(30), today.plusDays(config.examDaysAhead));
            List<Exam> parsed = DataParser.parseExams(r[0], r[1], zone.get(), subjectNames, r[2], r[3]);
            // organizers are teacher abbreviations ("Sn/Rd"): show them like the timetable, where known
            if (schedule != null) {
                Map<String, String> teacherNames = schedule.teacherDisplayNames();
                for (Exam x : parsed) {
                    if (!x.organizer.isEmpty()) {
                        x.organizer = DataParser.mapAbbreviations(x.organizer, teacherNames);
                    }
                }
            }
            exams = parsed;
        } catch (SchulmanagerException e) {
            error = "Exams: " + e.getMessage();
        }

        List<Homework> homework = null;
        try {
            homework = DataParser.parseHomework(client.fetchHomework(info), subjectNames);
        } catch (ModuleUnavailableException e) {
            homework = new ArrayList<>(); // school does not show homework to families
        } catch (SchulmanagerException e) {
            error = "Homework: " + e.getMessage();
        }

        if (error != null) {
            logger.debug("Schulmanager student {}: {}", studentId, error);
        }
        handler.updateData(info, schedule, exams, homework, error);
        return schedule == null && exams == null && homework == null ? error : null;
    }
}
