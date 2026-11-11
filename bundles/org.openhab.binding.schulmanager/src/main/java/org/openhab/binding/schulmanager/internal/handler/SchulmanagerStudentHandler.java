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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.schulmanager.internal.api.StudentInfo;
import org.openhab.binding.schulmanager.internal.config.StudentConfiguration;
import org.openhab.binding.schulmanager.internal.model.DataParser.Schedule;
import org.openhab.binding.schulmanager.internal.model.Exam;
import org.openhab.binding.schulmanager.internal.model.Format;
import org.openhab.binding.schulmanager.internal.model.Homework;
import org.openhab.binding.schulmanager.internal.model.Lesson;
import org.openhab.binding.schulmanager.internal.model.Period;
import org.openhab.binding.schulmanager.internal.model.WidgetJson;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * One child: timetable, exams and homework. The data is fetched by the account bridge; this handler keeps it and
 * recalculates the time-dependent channels (current/next lesson ...) every minute without contacting Schulmanager.
 *
 * @author Simon Scherer - Initial contribution
 */
@NonNullByDefault
public class SchulmanagerStudentHandler extends BaseThingHandler {

    private final Supplier<ZoneId> zone;

    private String studentId = "";
    private @Nullable ScheduledFuture<?> tickJob;

    private @Nullable StudentInfo info;
    private @Nullable Schedule schedule;
    private @Nullable List<Exam> exams;
    private @Nullable List<Homework> homework;

    private final Map<String, String> lastPublished = new HashMap<>();
    private @Nullable String lastChangeSignature;
    private @Nullable Set<String> knownHomework;
    private @Nullable Set<String> knownExams;

    public SchulmanagerStudentHandler(Thing thing, Supplier<ZoneId> zone) {
        super(thing);
        this.zone = zone;
    }

    public String getStudentId() {
        return studentId;
    }

    @Override
    public void initialize() {
        StudentConfiguration cfg = getConfigAs(StudentConfiguration.class);
        studentId = cfg.studentId.trim();
        if (studentId.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error.student-id-missing");
            return;
        }
        Bridge bridge = getBridge();
        if (bridge == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error.bridge-missing");
            return;
        }
        if (bridge.getStatus() != ThingStatus.ONLINE) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        } else {
            updateStatus(ThingStatus.UNKNOWN);
        }
        tickJob = scheduler.scheduleWithFixedDelay(this::tick, 60, 60, TimeUnit.SECONDS);
    }

    @Override
    public void dispose() {
        ScheduledFuture<?> job = tickJob;
        if (job != null) {
            job.cancel(true);
        }
        tickJob = null;
        super.dispose();
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        if (bridgeStatusInfo.getStatus() != ThingStatus.ONLINE) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        } else if (getThing().getStatus() != ThingStatus.ONLINE) {
            updateStatus(ThingStatus.UNKNOWN);
            Bridge bridge = getBridge();
            if (bridge != null && bridge.getHandler() instanceof SchulmanagerAccountHandler account) {
                scheduler.execute(() -> account.refreshStudent(this));
            }
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            Bridge bridge = getBridge();
            if (bridge != null && bridge.getHandler() instanceof SchulmanagerAccountHandler account) {
                account.requestRefresh();
            }
        }
    }

    // ================================================================== data from the bridge

    void setUnknownStudent(String knownIds) {
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                "@text/offline.conf-error.student-unknown [\"" + studentId + "\", \"" + knownIds + "\"]");
    }

    synchronized void updateData(StudentInfo info, @Nullable Schedule newSchedule, @Nullable List<Exam> newExams,
            @Nullable List<Homework> newHomework, @Nullable String error) {
        this.info = info;
        if (newSchedule != null) {
            detectScheduleChange(newSchedule);
            schedule = newSchedule;
        }
        if (newExams != null) {
            detectNewExams(newExams);
            exams = newExams;
        }
        if (newHomework != null) {
            detectNewHomework(newHomework);
            homework = newHomework;
        }
        if (schedule == null && exams == null && homework == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, error);
            return;
        }
        updateStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE, error);
        updateProperty("name", info.getName());
        updateState(GROUP_INFO + "#" + CH_INFO_SCHOOL, new StringType(info.schoolName));
        updateState(GROUP_INFO + "#" + CH_INFO_LAST_UPDATE, new DateTimeType(ZonedDateTime.now(zone.get())));
        publishAll();
    }

    private synchronized void tick() {
        if (info != null) {
            publishAll();
        }
    }

    private void publishAll() {
        ZoneId z = zone.get();
        LocalDateTime now = LocalDateTime.now(z);
        Schedule schedule = this.schedule;
        List<Exam> exams = this.exams;
        List<Homework> homework = this.homework;
        if (schedule != null) {
            publishSchedule(schedule, now, z);
            publishIfChanged(GROUP_SCHEDULE, CH_WIDGET, WidgetJson.schedule(schedule, now).toString());
        }
        if (exams != null) {
            publishExams(exams, now.toLocalDate(), z);
            publishIfChanged(GROUP_EXAMS, CH_WIDGET, WidgetJson.exams(exams, now.toLocalDate()).toString());
        }
        if (homework != null) {
            publishHomework(homework, now.toLocalDate(), z);
            publishIfChanged(GROUP_HOMEWORK, CH_WIDGET, WidgetJson.homework(homework, now.toLocalDate()).toString());
        }
    }

    // ================================================================== schedule

    private void publishSchedule(Schedule schedule, LocalDateTime now, ZoneId z) {
        LocalDate today = now.toLocalDate();
        List<Lesson> todays = schedule.lessonsOn(today);
        List<Lesson> tomorrows = schedule.lessonsOn(today.plusDays(1));

        set(GROUP_SCHEDULE, CH_TODAY, new StringType(dayText(todays)));
        set(GROUP_SCHEDULE, CH_TOMORROW, new StringType(dayText(tomorrows)));

        LocalDate nextDay = null;
        for (Map.Entry<LocalDate, List<Lesson>> e : schedule.days.tailMap(today, false).entrySet()) {
            if (e.getValue().stream().anyMatch(l -> !l.isCancelled())) {
                nextDay = e.getKey();
                break;
            }
        }
        if (nextDay != null) {
            set(GROUP_SCHEDULE, CH_NEXT_DAY, new StringType(
                    Format.dayLabel(nextDay) + "\n" + dayText(schedule.lessonsOn(nextDay))));
            set(GROUP_SCHEDULE, CH_NEXT_DAY_DATE, new DateTimeType(nextDay.atStartOfDay(z)));
            set(GROUP_SCHEDULE, CH_FIRST_LESSON_NEXT_DAY, at(nextDay, first(schedule.lessonsOn(nextDay)), true, z));
        } else {
            set(GROUP_SCHEDULE, CH_NEXT_DAY, new StringType("Kein Unterricht im geladenen Zeitraum"));
            set(GROUP_SCHEDULE, CH_NEXT_DAY_DATE, UnDefType.UNDEF);
            set(GROUP_SCHEDULE, CH_FIRST_LESSON_NEXT_DAY, UnDefType.UNDEF);
        }

        set(GROUP_SCHEDULE, CH_FIRST_LESSON_TODAY, at(today, first(todays), true, z));
        set(GROUP_SCHEDULE, CH_LAST_LESSON_TODAY, at(today, last(todays), false, z));

        LocalTime t = now.toLocalTime();
        Lesson current = null;
        Lesson next = null;
        for (Lesson l : todays) {
            if (l.isCancelled() || l.start == null || l.end == null) {
                continue;
            }
            if (!t.isBefore(l.start) && t.isBefore(l.end) && current == null) {
                current = l;
            } else if (l.start.isAfter(t) && next == null) {
                next = l;
            }
        }
        String currentText;
        if (current != null) {
            currentText = current.toLine() + " (bis " + current.end + ")";
        } else if (next != null && isAfterStart(t, first(todays))) {
            currentText = "Pause";
        } else {
            currentText = "Kein Unterricht";
        }
        set(GROUP_SCHEDULE, CH_CURRENT_LESSON, new StringType(currentText));
        set(GROUP_SCHEDULE, CH_NEXT_LESSON, new StringType(next != null ? next.toLine() : "Heute keine weitere Stunde"));

        List<Lesson> changesToday = todays.stream().filter(Lesson::isChanged).toList();
        List<Lesson> changesTomorrow = tomorrows.stream().filter(Lesson::isChanged).toList();
        set(GROUP_SCHEDULE, CH_CHANGES_TODAY, new DecimalType(changesToday.size()));
        set(GROUP_SCHEDULE, CH_CHANGES_TOMORROW, new DecimalType(changesTomorrow.size()));
        StringBuilder sb = new StringBuilder();
        appendChanges(sb, "Heute", changesToday);
        appendChanges(sb, "Morgen", changesTomorrow);
        set(GROUP_SCHEDULE, CH_CHANGES_SUMMARY,
                new StringType(sb.length() == 0 ? "Keine Stundenplanänderungen für heute und morgen" : sb.toString().trim()));

        if (isLinked(GROUP_SCHEDULE + "#" + CH_SCHEDULE_JSON)) {
            publishIfChanged(GROUP_SCHEDULE, CH_SCHEDULE_JSON, scheduleJson(schedule).toString());
        }
    }

    private static void appendChanges(StringBuilder sb, String label, List<Lesson> changes) {
        if (changes.isEmpty()) {
            return;
        }
        sb.append(label).append(" (").append(changes.size()).append(changes.size() == 1 ? " Änderung" : " Änderungen")
                .append("):\n");
        for (Lesson l : changes) {
            sb.append("  ").append(l.toLine()).append('\n');
        }
    }

    private static String dayText(List<Lesson> lessons) {
        if (lessons.isEmpty()) {
            return "Kein Unterricht";
        }
        StringBuilder sb = new StringBuilder();
        for (Lesson l : lessons) {
            sb.append(l.toLine()).append('\n');
        }
        return sb.toString().trim();
    }

    private static boolean isAfterStart(LocalTime t, @Nullable Lesson first) {
        LocalTime start = first == null ? null : first.start;
        return start != null && t.isAfter(start);
    }

    private static @Nullable Lesson first(List<Lesson> lessons) {
        return lessons.stream().filter(l -> !l.isCancelled() && l.start != null).findFirst().orElse(null);
    }

    private static @Nullable Lesson last(List<Lesson> lessons) {
        Lesson last = null;
        for (Lesson l : lessons) {
            if (!l.isCancelled() && l.end != null && (last == null || l.end.isAfter(last.end))) {
                last = l;
            }
        }
        return last;
    }

    private static State at(LocalDate day, @Nullable Lesson l, boolean start, ZoneId z) {
        if (l == null) {
            return UnDefType.UNDEF;
        }
        LocalTime t = start ? l.start : l.end;
        return t == null ? UnDefType.UNDEF : new DateTimeType(day.atTime(t).atZone(z));
    }

    private void detectScheduleChange(Schedule newSchedule) {
        LocalDate today = LocalDate.now(zone.get());
        StringBuilder sig = new StringBuilder();
        for (Map.Entry<LocalDate, List<Lesson>> e : newSchedule.days.tailMap(today, true).entrySet()) {
            for (Lesson l : e.getValue()) {
                if (l.isChanged()) {
                    sig.append(l.id).append('|').append(l.status).append('|').append(l.subject).append('|')
                            .append(l.teachers).append('|').append(l.rooms).append(';');
                }
            }
        }
        String signature = sig.toString();
        String last = lastChangeSignature;
        if (last != null && !last.equals(signature)) {
            triggerChannel(GROUP_SCHEDULE + "#" + CH_SCHEDULE_CHANGED, "CHANGED");
        }
        lastChangeSignature = signature;
    }

    private JsonObject scheduleJson(Schedule schedule) {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", 1);
        StudentInfo info = this.info;
        if (info != null) {
            root.addProperty("student_id", info.id);
            root.addProperty("name", info.getName());
            root.addProperty("school", info.schoolName);
        }
        JsonObject range = new JsonObject();
        range.addProperty("start", String.valueOf(schedule.rangeStart));
        range.addProperty("end", String.valueOf(schedule.rangeEnd));
        root.add("range", range);
        JsonArray periods = new JsonArray();
        for (Period p : schedule.periods) {
            JsonObject o = new JsonObject();
            o.addProperty("period", p.number);
            o.addProperty("start", p.start == null ? null : p.start.toString());
            o.addProperty("end", p.end == null ? null : p.end.toString());
            periods.add(o);
        }
        root.add("periods", periods);
        JsonArray days = new JsonArray();
        for (Map.Entry<LocalDate, List<Lesson>> e : schedule.days.entrySet()) {
            JsonObject d = new JsonObject();
            d.addProperty("date", e.getKey().toString());
            JsonArray lessons = new JsonArray();
            for (Lesson l : e.getValue()) {
                JsonObject o = new JsonObject();
                o.addProperty("id", l.id);
                o.addProperty("period", l.period);
                o.addProperty("start", l.start == null ? null : l.start.toString());
                o.addProperty("end", l.end == null ? null : l.end.toString());
                o.addProperty("subject", l.subject);
                o.addProperty("subject_full", l.subjectFull);
                o.addProperty("group", l.group);
                o.add("teachers", strings(l.teachers));
                o.add("teacher_names", strings(l.teacherNames));
                o.add("rooms", strings(l.rooms));
                o.addProperty("kind", l.kind);
                o.addProperty("status", l.status);
                o.add("changes", strings(l.changes));
                o.add("notes", strings(l.notes));
                if (l.original != null) {
                    JsonObject orig = new JsonObject();
                    orig.addProperty("subject", l.original.subject);
                    orig.add("teachers", strings(l.original.teachers));
                    orig.add("rooms", strings(l.original.rooms));
                    o.add("original", orig);
                }
                lessons.add(o);
            }
            d.add("lessons", lessons);
            days.add(d);
        }
        root.add("days", days);
        return root;
    }

    // ================================================================== exams

    private void publishExams(List<Exam> exams, LocalDate today, ZoneId z) {
        List<Exam> upcoming = exams.stream().filter(x -> !x.schoolEvent && !x.date.isBefore(today)).toList();
        List<Exam> events = exams.stream().filter(x -> x.schoolEvent && !x.lastDay().isBefore(today)).toList();
        if (upcoming.isEmpty()) {
            set(GROUP_EXAMS, CH_EXAM_NEXT, new StringType("Keine Arbeiten eingetragen"));
            set(GROUP_EXAMS, CH_EXAM_NEXT_DATE, UnDefType.UNDEF);
            set(GROUP_EXAMS, CH_EXAM_DAYS_UNTIL, UnDefType.UNDEF);
        } else {
            Exam n = upcoming.get(0);
            set(GROUP_EXAMS, CH_EXAM_NEXT, new StringType(examLine(n)));
            set(GROUP_EXAMS, CH_EXAM_NEXT_DATE,
                    new DateTimeType((n.start != null ? n.date.atTime(n.start) : n.date.atStartOfDay()).atZone(z)));
            set(GROUP_EXAMS, CH_EXAM_DAYS_UNTIL, new DecimalType(ChronoUnit.DAYS.between(today, n.date)));
        }
        long in14 = upcoming.stream().filter(x -> !x.date.isAfter(today.plusDays(14))).count();
        set(GROUP_EXAMS, CH_EXAM_COUNT_14, new DecimalType(in14));
        StringBuilder sb = new StringBuilder();
        upcoming.stream().limit(10).forEach(x -> sb.append(examLine(x)).append('\n'));
        set(GROUP_EXAMS, CH_EXAM_LIST, new StringType(sb.length() == 0 ? "Keine Arbeiten eingetragen" : sb.toString().trim()));
        if (events.isEmpty()) {
            set(GROUP_EXAMS, CH_EXAM_NEXT_EVENT, new StringType("Keine Schultermine"));
            set(GROUP_EXAMS, CH_EXAM_NEXT_EVENT_DATE, UnDefType.UNDEF);
        } else {
            Exam e = events.get(0);
            set(GROUP_EXAMS, CH_EXAM_NEXT_EVENT, new StringType(examLine(e)));
            set(GROUP_EXAMS, CH_EXAM_NEXT_EVENT_DATE,
                    new DateTimeType((e.start != null ? e.date.atTime(e.start) : e.date.atStartOfDay()).atZone(z)));
        }
        if (isLinked(GROUP_EXAMS + "#" + CH_EXAM_JSON)) {
            JsonArray arr = new JsonArray();
            for (Exam x : exams) {
                JsonObject o = new JsonObject();
                o.addProperty("date", x.date.toString());
                o.addProperty("type", x.type);
                o.addProperty("subject", x.subject);
                o.addProperty("subject_short", x.subjectShort);
                o.addProperty("period", x.period);
                o.addProperty("start", x.start == null ? null : x.start.toString());
                o.addProperty("end", x.end == null ? null : x.end.toString());
                o.addProperty("comment", x.comment);
                o.addProperty("school_event", x.schoolEvent);
                arr.add(o);
            }
            publishIfChanged(GROUP_EXAMS, CH_EXAM_JSON, arr.toString());
        }
    }

    static String examLine(Exam x) {
        StringBuilder sb = new StringBuilder(Format.shortDate(x.date));
        if (x.endDate != null && x.endDate.isAfter(x.date)) {
            sb.append(" – ").append(Format.shortDate(x.endDate));
        }
        sb.append(": ").append(x.title());
        if (!x.location.isEmpty()) {
            sb.append(", ").append(x.location);
        }
        if (!x.period.isEmpty() && !"X".equals(x.period)) {
            sb.append(" (").append(x.period).append(". Std)");
        } else if (x.start != null) {
            sb.append(" (").append(x.start).append(")");
        }
        if (!x.comment.isEmpty()) {
            sb.append(" – ").append(x.comment.replace('\n', ' '));
        }
        return sb.toString();
    }

    private void detectNewExams(List<Exam> newExams) {
        Set<String> ids = new HashSet<>();
        Set<String> known = knownExams;
        for (Exam x : newExams) {
            String key = x.schoolEvent ? x.id : x.id + "|" + x.date;
            ids.add(key);
            if (known != null && !known.contains(key) && !x.schoolEvent) {
                triggerChannel(GROUP_EXAMS + "#" + CH_EXAM_NEW, examLine(x));
            }
        }
        knownExams = ids;
    }

    // ================================================================== homework

    private void publishHomework(List<Homework> homework, LocalDate today, ZoneId z) {
        List<Homework> recent = homework.stream().filter(h -> h.date != null && !h.date.isBefore(today.minusDays(7)))
                .toList();
        if (homework.isEmpty()) {
            set(GROUP_HOMEWORK, CH_HW_LATEST, new StringType("Keine Hausaufgaben"));
            set(GROUP_HOMEWORK, CH_HW_LATEST_DATE, UnDefType.UNDEF);
        } else {
            Homework h = homework.get(0);
            set(GROUP_HOMEWORK, CH_HW_LATEST, new StringType(homeworkLine(h)));
            set(GROUP_HOMEWORK, CH_HW_LATEST_DATE, h.date == null ? UnDefType.UNDEF : new DateTimeType(h.date.atStartOfDay(z)));
        }
        set(GROUP_HOMEWORK, CH_HW_COUNT_RECENT, new DecimalType(recent.size()));
        StringBuilder sb = new StringBuilder();
        homework.stream().filter(h -> h.date != null && !h.date.isBefore(today.minusDays(14))).limit(20)
                .forEach(h -> sb.append(homeworkLine(h)).append('\n'));
        set(GROUP_HOMEWORK, CH_HW_LIST,
                new StringType(sb.length() == 0 ? "Keine Hausaufgaben in den letzten 14 Tagen" : sb.toString().trim()));
        if (isLinked(GROUP_HOMEWORK + "#" + CH_HW_JSON)) {
            JsonArray arr = new JsonArray();
            for (Homework h : homework) {
                JsonObject o = new JsonObject();
                o.addProperty("date", h.date == null ? null : h.date.toString());
                o.addProperty("subject", h.subject);
                o.addProperty("homework", h.text);
                arr.add(o);
            }
            publishIfChanged(GROUP_HOMEWORK, CH_HW_JSON, arr.toString());
        }
    }

    static String homeworkLine(Homework h) {
        String d = h.date == null ? "" : Format.shortDate(h.date) + " ";
        return d + (h.subject.isEmpty() ? "" : h.subject + ": ") + h.text.replace('\n', ' ');
    }

    private void detectNewHomework(List<Homework> newHomework) {
        Set<String> keys = new HashSet<>();
        List<Homework> fresh = new ArrayList<>();
        Set<String> known = knownHomework;
        for (Homework h : newHomework) {
            keys.add(h.key());
            if (known != null && !known.contains(h.key())) {
                fresh.add(h);
            }
        }
        for (Homework h : fresh) {
            triggerChannel(GROUP_HOMEWORK + "#" + CH_HW_NEW, homeworkLine(h));
        }
        knownHomework = keys;
    }

    // ================================================================== helpers

    private void set(String group, String channel, State state) {
        updateState(group + "#" + channel, state);
    }

    /**
     * The JSON channels are large and the handler recalculates every minute; only send them when the content changed
     * (or the channel was linked since), to keep the event bus and the UI connections quiet.
     */
    private void publishIfChanged(String group, String channel, String json) {
        String uid = group + "#" + channel;
        if (!isLinked(uid)) {
            lastPublished.remove(uid);
            return;
        }
        if (json.equals(lastPublished.get(uid))) {
            return;
        }
        lastPublished.put(uid, json);
        updateState(uid, new StringType(json));
    }

    private static JsonArray strings(List<String> list) {
        JsonArray a = new JsonArray();
        list.forEach(a::add);
        return a;
    }
}
