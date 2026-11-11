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
package org.openhab.binding.schulmanager.internal.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Turns the raw Schulmanager JSON into the binding's model. The lesson logic is a port of schedule_export.py and
 * lesson_info.py of the MIT licensed Home Assistant integration by MrIcemanLE.
 *
 * @author Simon Scherer - Initial contribution
 */
public final class DataParser {

    private DataParser() {
    }

    /** Parsed timetable: class hour times and the lessons per day (sorted). */
    public static class Schedule {
        public final List<Period> periods = new ArrayList<>();
        public final TreeMap<LocalDate, List<Lesson>> days = new TreeMap<>();
        public LocalDate rangeStart;
        public LocalDate rangeEnd;

        public List<Lesson> lessonsOn(LocalDate day) {
            return days.getOrDefault(day, List.of());
        }

        /** Teacher abbreviation -> name as configured for display (teacherDisplay), from all lessons. */
        public Map<String, String> teacherDisplayNames() {
            Map<String, String> map = new HashMap<>();
            for (List<Lesson> ls : days.values()) {
                for (Lesson l : ls) {
                    for (int i = 0; i < l.teachers.size() && i < l.shownTeachers.size(); i++) {
                        map.putIfAbsent(l.teachers.get(i), l.shownTeachers.get(i));
                    }
                }
            }
            return map;
        }
    }

    /** "Sn/Rd" -> "Schneider/Reindl" for the abbreviations known from the timetable; unknown ones stay. */
    public static String mapAbbreviations(String text, Map<String, String> names) {
        if (names.isEmpty()) {
            return text;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[^/,;\\s]+").matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String n = names.get(m.group());
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(n != null ? n : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ================================================================== schedule

    public static Schedule parseSchedule(JsonElement lessonsData, JsonElement classHoursData, LocalDate rangeStart,
            LocalDate rangeEnd) {
        return parseSchedule(lessonsData, classHoursData, rangeStart, rangeEnd, Lesson.TEACHER_ABBREVIATION,
                Lesson.SUBJECT_ABBREVIATION, SubjectNames.NONE);
    }

    /**
     * @param teacherDisplay how teachers are shown in lines and in the timetable: abbreviation, lastname or name
     * @param subjectDisplay how subjects are shown: abbreviation or name
     */
    public static Schedule parseSchedule(JsonElement lessonsData, JsonElement classHoursData, LocalDate rangeStart,
            LocalDate rangeEnd, String teacherDisplay, String subjectDisplay, SubjectNames subjectNames) {
        Schedule result = new Schedule();
        result.rangeStart = rangeStart;
        result.rangeEnd = rangeEnd;

        Map<String, JsonObject> classHours = new HashMap<>();
        if (classHoursData != null && classHoursData.isJsonArray()) {
            for (JsonElement e : classHoursData.getAsJsonArray()) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject ch = e.getAsJsonObject();
                if (ch.has("id")) {
                    classHours.put(ch.get("id").getAsString(), ch);
                }
                Period p = new Period();
                p.number = normalizeHour(str(ch, "number"));
                p.start = time(str(ch, "from"));
                p.end = time(str(ch, "until"));
                result.periods.add(p);
            }
            result.periods
                    .sort(Comparator.comparing((Period p) -> periodSortKey(p.number)).thenComparing(p -> p.number));
        }

        Map<LocalDate, List<Lesson>> raw = new TreeMap<>();
        if (lessonsData != null && lessonsData.isJsonArray()) {
            for (JsonElement e : lessonsData.getAsJsonArray()) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject entry = e.getAsJsonObject();
                Lesson l = exportLesson(entry, classHours);
                subjectNames.learn(l.subject, l.subjectFull);
                if (l.original != null) {
                    subjectNames.learn(l.original.subject, l.original.subjectFull);
                }
                l.applyDisplay(teacherDisplay, subjectDisplay, subjectNames);
                if (l.date != null) {
                    raw.computeIfAbsent(l.date, d -> new ArrayList<>()).add(l);
                }
            }
        }
        for (LocalDate d = rangeStart; d != null && !d.isAfter(rangeEnd); d = d.plusDays(1)) {
            raw.putIfAbsent(d, new ArrayList<>());
        }
        for (Map.Entry<LocalDate, List<Lesson>> day : raw.entrySet()) {
            List<Lesson> merged = mergeSplitRooms(day.getValue());
            Map<String, Integer> seen = new HashMap<>();
            for (Lesson l : merged) {
                int count = seen.merge(l.id, 1, Integer::sum);
                if (count > 1) {
                    l.id = l.id + "-" + count;
                }
            }
            merged.sort(Comparator.comparing((Lesson l) -> l.start == null ? LocalTime.MAX : l.start)
                    .thenComparing(l -> periodSortKey(l.period)));
            result.days.put(day.getKey(), merged);
        }
        return result;
    }

    static Lesson exportLesson(JsonObject entry, Map<String, JsonObject> classHours) {
        Lesson l = new Lesson();
        String sourceType = str(entry, "type");
        JsonObject actual = obj(entry, "actualLesson");
        if (actual == null) {
            actual = obj(entry, "event");
        }
        if (actual == null) {
            actual = new JsonObject();
        }
        JsonObject original = null;
        for (JsonElement o : arr(entry, "originalLessons")) {
            if (o.isJsonObject()) {
                original = o.getAsJsonObject();
                break;
            }
        }

        switch (sourceType) {
            case "regularLesson" -> set(l, "lesson", Lesson.STATUS_SCHEDULED);
            case "changedLesson", "specialLesson" -> set(l, "lesson", Lesson.STATUS_CHANGED);
            case "cancelledLesson" -> set(l, "lesson", Lesson.STATUS_CANCELLED);
            case "event" -> set(l, "event", Lesson.STATUS_SCHEDULED);
            default -> set(l, "unknown", "unknown");
        }
        if (bool(entry, "isCancelled") && "lesson".equals(l.kind)) {
            l.status = Lesson.STATUS_CANCELLED;
        }

        Summary current = summary(actual);
        Summary before = original != null ? summary(original) : null;
        if (l.isCancelled()) {
            l.changes.add("cancellation");
            if (before != null) {
                current = before.copyWithoutTeachersAndRooms();
            }
        } else if (before != null) {
            if (!current.subject.equals(before.subject)) {
                l.changes.add("subject");
            }
            if (!current.teachers.equals(before.teachers)) {
                l.changes.add("teacher");
            }
            if (!current.rooms.equals(before.rooms)) {
                l.changes.add("room");
            }
        }
        if ("specialLesson".equals(sourceType)) {
            l.changes.add("special");
        }
        if (current.group == null && before != null) {
            current.group = before.group;
        }

        for (String note : new String[] { str(entry, "comment"), str(actual, "comment"),
                str(entry, "substitutionText") }) {
            String n = note.trim();
            if (!n.isEmpty() && !l.notes.contains(n)) {
                l.notes.add(n);
            }
        }

        String day = str(entry, "date");
        if (day.isEmpty()) {
            day = str(entry, "start");
        }
        l.date = date(day);

        JsonObject ch = obj(entry, "classHour");
        if (ch != null) {
            l.period = normalizeHour(str(ch, "number"));
            JsonObject times = ch.has("id") ? classHours.get(ch.get("id").getAsString()) : null;
            String from = str(times != null ? times : ch, "from");
            String until = str(times != null ? times : ch, "until");
            if (times != null && l.date != null) {
                // fromByDay/untilByDay use the JavaScript weekday (0 = Sunday)
                int jsDay = l.date.getDayOfWeek().getValue() % 7;
                String f = indexStr(times, "fromByDay", jsDay);
                String u = indexStr(times, "untilByDay", jsDay);
                if (!f.isEmpty()) {
                    from = f;
                }
                if (!u.isEmpty()) {
                    until = u;
                }
            }
            l.start = time(from);
            l.end = time(until);
        }

        l.subject = current.subject;
        l.subjectFull = current.subjectFull;
        l.group = current.group;
        l.teachers = current.teachers;
        l.teacherNames = current.teacherNames;
        l.teacherLastnames = current.teacherLastnames;
        l.rooms = current.rooms;
        l.sourceType = sourceType;
        if (before != null) {
            Lesson.Original o = new Lesson.Original();
            o.subject = before.subject;
            o.subjectFull = before.subjectFull;
            o.teachers = before.teachers;
            o.teacherNames = before.teacherNames;
            o.teacherLastnames = before.teacherLastnames;
            o.rooms = before.rooms;
            o.group = before.group;
            l.original = o;
        }

        // id: lessonId is a weekly slot, unique only together with the date
        String lessonId = str(actual, "lessonId");
        if (lessonId.isEmpty() && original != null) {
            lessonId = str(original, "lessonId");
        }
        String dayIso = l.date == null ? "" : l.date.toString();
        if (!lessonId.isEmpty()) {
            l.id = dayIso + "-" + lessonId;
        } else if (!str(actual, "substitutionId").isEmpty()) {
            l.id = dayIso + "-s" + str(actual, "substitutionId");
        } else {
            l.id = dayIso + "-" + l.period + "-"
                    + (current.subject.isEmpty() && before != null ? before.subject : current.subject);
        }
        return l;
    }

    private static void set(Lesson l, String kind, String status) {
        l.kind = kind;
        l.status = status;
    }

    /** A course split across rooms arrives as one entry per room with the same lessonId. */
    static List<Lesson> mergeSplitRooms(List<Lesson> lessons) {
        List<Lesson> merged = new ArrayList<>();
        Map<String, Lesson> firstById = new LinkedHashMap<>();
        for (Lesson l : lessons) {
            Lesson first = firstById.get(l.id);
            if (first == null || !sameExceptRooms(first, l)) {
                firstById.putIfAbsent(l.id, l);
                merged.add(l);
                continue;
            }
            addAll(first.rooms, l.rooms);
            addAll(first.changes, l.changes);
            addAll(first.notes, l.notes);
            if (first.original != null && l.original != null) {
                addAll(first.original.rooms, l.original.rooms);
            }
        }
        return merged;
    }

    private static boolean sameExceptRooms(Lesson a, Lesson b) {
        boolean origSame = a.original == null ? b.original == null
                : b.original != null && a.original.subject.equals(b.original.subject)
                        && a.original.teachers.equals(b.original.teachers)
                        && Objects.equals(a.original.group, b.original.group);
        return Objects.equals(a.date, b.date) && a.period.equals(b.period) && Objects.equals(a.start, b.start)
                && a.subject.equals(b.subject) && a.teachers.equals(b.teachers) && Objects.equals(a.group, b.group)
                && a.kind.equals(b.kind) && a.status.equals(b.status) && a.sourceType.equals(b.sourceType) && origSame;
    }

    private static void addAll(List<String> target, List<String> values) {
        for (String v : values) {
            if (!target.contains(v)) {
                target.add(v);
            }
        }
    }

    private static class Summary {
        String subject = "";
        String subjectFull = "";
        List<String> teachers = new ArrayList<>();
        List<String> teacherNames = new ArrayList<>();
        List<String> teacherLastnames = new ArrayList<>();
        List<String> rooms = new ArrayList<>();
        String group;

        Summary copyWithoutTeachersAndRooms() {
            Summary s = new Summary();
            s.subject = subject;
            s.subjectFull = subjectFull;
            s.group = group;
            return s;
        }
    }

    private static Summary summary(JsonObject rec) {
        Summary s = new Summary();
        JsonElement subject = rec.get("subject");
        if (subject != null && subject.isJsonObject()) {
            JsonObject so = subject.getAsJsonObject();
            s.subject = firstNonEmpty(str(so, "abbreviation"), str(so, "shortName"), str(so, "name"));
            s.subjectFull = firstNonEmpty(str(so, "name"), str(so, "longName"), s.subject);
        } else if (subject != null && subject.isJsonPrimitive()) {
            s.subject = s.subjectFull = subject.getAsString();
        }
        for (JsonElement t : arr(rec, "teachers")) {
            if (!t.isJsonObject()) {
                continue;
            }
            JsonObject to = t.getAsJsonObject();
            String full = (str(to, "firstname").trim() + " " + str(to, "lastname").trim()).trim();
            String last = str(to, "lastname").trim();
            String abbr = str(to, "abbreviation").trim();
            if (abbr.isEmpty()) {
                abbr = full;
            }
            if (abbr.isEmpty()) {
                continue;
            }
            // the abbreviation stays the key for change detection; names only where the school provides them
            s.teachers.add(abbr);
            s.teacherNames.add(full.isEmpty() ? abbr : full);
            s.teacherLastnames.add(last.isEmpty() ? abbr : last);
        }
        List<JsonElement> rooms = new ArrayList<>();
        if (rec.has("room")) {
            rooms.add(rec.get("room"));
        }
        arr(rec, "rooms").forEach(rooms::add);
        for (JsonElement r : rooms) {
            String name = "";
            if (r != null && r.isJsonObject()) {
                name = firstNonEmpty(str(r.getAsJsonObject(), "name"), str(r.getAsJsonObject(), "shortName"));
            } else if (r != null && r.isJsonPrimitive()) {
                name = r.getAsString();
            }
            if (!name.isEmpty() && !s.rooms.contains(name)) {
                s.rooms.add(name);
            }
        }
        List<String> groups = new ArrayList<>();
        for (JsonElement g : arr(rec, "studentGroups")) {
            if (g.isJsonObject() && !str(g.getAsJsonObject(), "name").isEmpty()) {
                groups.add(str(g.getAsJsonObject(), "name"));
            }
        }
        s.group = groups.isEmpty() ? null : String.join(", ", groups);
        return s;
    }

    // ================================================================== exams

    public static List<Exam> parseExams(JsonElement examsData, JsonElement eventsData, ZoneId zone) {
        return parseExams(examsData, eventsData, zone, SubjectNames.NONE);
    }

    public static List<Exam> parseExams(JsonElement examsData, JsonElement eventsData, ZoneId zone,
            SubjectNames subjectNames) {
        return parseExams(examsData, eventsData, zone, subjectNames, null, null);
    }

    /**
     * @param calendarData calendar/get-events-for-user (preferred source for school events, like the app);
     *            if null, the events of the exams module (eventsData) are used
     * @param categoriesData calendar/get-event-categories
     */
    public static List<Exam> parseExams(JsonElement examsData, JsonElement eventsData, ZoneId zone,
            SubjectNames subjectNames, JsonElement calendarData, JsonElement categoriesData) {
        List<Exam> list = new ArrayList<>();
        if (examsData != null && examsData.isJsonArray()) {
            for (JsonElement e : examsData.getAsJsonArray()) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject o = e.getAsJsonObject();
                Exam x = new Exam();
                x.id = str(o, "id");
                x.date = date(str(o, "date"));
                JsonObject type = obj(o, "type");
                if (type != null) {
                    x.type = str(type, "name");
                    x.color = str(type, "color");
                }
                JsonObject subj = obj(o, "subject");
                if (subj != null) {
                    x.subject = firstNonEmpty(str(subj, "name"), str(subj, "abbreviation"));
                    x.subjectShort = firstNonEmpty(str(subj, "abbreviation"), x.subject);
                }
                if (x.subject.isEmpty()) {
                    x.subject = str(o, "subjectText");
                }
                String own = subjectNames.lookup(x.subjectShort, x.subject);
                if (own != null) {
                    x.subject = own;
                }
                x.comment = str(o, "comment").trim();
                JsonObject sch = obj(o, "startClassHour");
                JsonObject ech = obj(o, "endClassHour");
                if (sch != null) {
                    x.period = normalizeHour(str(sch, "number"));
                    x.start = time(str(sch, "from"));
                    x.end = time(str(ech != null ? ech : sch, "until"));
                    if (ech != null && !normalizeHour(str(ech, "number")).equals(x.period)) {
                        x.period = x.period + "–" + normalizeHour(str(ech, "number"));
                    }
                }
                if (x.date != null) {
                    list.add(x);
                }
            }
        }
        JsonArray calendarEvents = null;
        if (calendarData != null && calendarData.isJsonObject()) {
            calendarEvents = new JsonArray();
            for (String k : new String[] { "nonRecurringEvents", "recurringEvents" }) {
                JsonElement a = calendarData.getAsJsonObject().get(k);
                if (a != null && a.isJsonArray()) {
                    calendarEvents.addAll(a.getAsJsonArray());
                }
            }
        }
        Map<String, String[]> categories = new HashMap<>();
        if (categoriesData != null && categoriesData.isJsonArray()) {
            for (JsonElement c : categoriesData.getAsJsonArray()) {
                if (c.isJsonObject() && c.getAsJsonObject().has("id")) {
                    JsonObject co = c.getAsJsonObject();
                    categories.put(co.get("id").getAsString(), new String[] { str(co, "name"), str(co, "color") });
                }
            }
        }
        JsonElement source = calendarEvents != null ? calendarEvents : eventsData;
        if (source != null && source.isJsonArray()) {
            for (JsonElement e : source.getAsJsonArray()) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject o = e.getAsJsonObject();
                Exam x = new Exam();
                x.schoolEvent = true;
                x.id = "event-" + str(o, "id");
                x.type = "Schultermin";
                x.subject = firstNonEmpty(str(o, "summary"), "Schultermin");
                x.comment = plainText(str(o, "description"));
                x.location = str(o, "location").trim();
                x.organizer = str(o, "organizer").trim();
                String catId = o.has("categoryId") && !o.get("categoryId").isJsonNull()
                        ? o.get("categoryId").getAsString()
                        : "";
                x.holiday = "-1".equals(catId);
                String[] cat = categories.get(catId);
                x.category = x.holiday ? "Ferien/Feiertage" : cat != null ? cat[0] : "";
                x.categoryColor = cat != null && cat[1] != null ? cat[1] : "";
                // Calendar events carry real UTC timestamps, unlike all other times of this API
                ZonedDateTime start = utc(str(o, "start"), zone);
                ZonedDateTime end = utc(str(o, "end"), zone);
                if (start == null) {
                    continue;
                }
                boolean allDay = bool(o, "allDay") || (str(o, "start").contains("T00:00:00")
                        && str(o, "end").contains("T00:00:00") && !str(o, "start").equals(str(o, "end")));
                if (allDay) {
                    // midnight markers of calendar dates, do not shift them; the end is exclusive
                    x.date = date(str(o, "start"));
                    LocalDate endExclusive = date(str(o, "end"));
                    if (x.date != null && endExclusive != null && endExclusive.isAfter(x.date.plusDays(1))) {
                        x.endDate = endExclusive.minusDays(1);
                    }
                } else {
                    x.date = start.toLocalDate();
                    x.start = start.toLocalTime();
                    x.end = end != null ? end.toLocalTime() : null;
                }
                list.add(x);
            }
        }
        list.sort(
                Comparator.comparing((Exam x) -> x.date).thenComparing(x -> x.start == null ? LocalTime.MIN : x.start));
        // only drop true duplicates (same entry delivered twice); separate entries stay, as in the Schulmanager app
        List<Exam> unique = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Exam x : list) {
            String key = x.id.isEmpty() || x.id.equals("event-")
                    ? x.schoolEvent + "|" + x.date + "|" + x.start + "|" + x.type + "|" + x.subject + "|" + x.period
                            + "|" + x.comment
                    : x.id + "|" + x.date;
            if (seen.add(key)) {
                unique.add(x);
            }
        }
        return unique;
    }

    // ================================================================== homework

    public static List<Homework> parseHomework(JsonArray data) {
        return parseHomework(data, SubjectNames.NONE);
    }

    public static List<Homework> parseHomework(JsonArray data, SubjectNames subjectNames) {
        List<Homework> list = new ArrayList<>();
        for (JsonElement e : data) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            Homework h = new Homework();
            h.date = date(str(o, "date"));
            JsonElement subj = o.get("subject");
            String abbr = "";
            if (subj != null && subj.isJsonObject()) {
                h.subject = firstNonEmpty(str(subj.getAsJsonObject(), "name"),
                        str(subj.getAsJsonObject(), "abbreviation"));
                abbr = str(subj.getAsJsonObject(), "abbreviation");
            } else {
                h.subject = str(o, "subject").trim();
            }
            // the key for "new homework" stays the original subject, so changing the names fires no events
            h.keySubject = h.subject;
            String own = subjectNames.lookup(abbr, h.subject);
            if (own != null) {
                h.subject = own;
            }
            h.text = str(o, "homework").trim();
            if (!h.text.isEmpty() || !h.subject.isEmpty()) {
                list.add(h);
            }
        }
        // newest first
        list.sort(Comparator.comparing((Homework h) -> h.date == null ? LocalDate.MIN : h.date).reversed()
                .thenComparing(h -> h.subject));
        return list;
    }

    // ================================================================== helpers

    public static String normalizeHour(String value) {
        String v = value == null ? "" : value.trim();
        while (v.endsWith(".")) {
            v = v.substring(0, v.length() - 1).trim();
        }
        return v;
    }

    private static int periodSortKey(String period) {
        try {
            return Integer.parseInt(period);
        } catch (NumberFormatException e) {
            return 1_000_000;
        }
    }

    /** Calendar descriptions may contain HTML; turn it into plain text with line breaks. */
    public static String plainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String s = html.replaceAll("(?i)<br\\s*/?>|</li>", "\n").replaceAll("(?i)</p>|</div>", "\n\n")
                .replaceAll("<[^>]+>", "");
        s = decodeEntities(s).replace(' ', ' ');
        return s.replaceAll("[ \t]+\n", "\n").replaceAll("\n[ \t]+", "\n").replaceAll("\n{3,}", "\n\n").trim();
    }

    private static final Map<String, String> ENTITIES = Map.ofEntries(Map.entry("nbsp", " "), Map.entry("lt", "<"),
            Map.entry("gt", ">"), Map.entry("quot", "\""), Map.entry("apos", "'"), Map.entry("auml", "ä"),
            Map.entry("ouml", "ö"), Map.entry("uuml", "ü"), Map.entry("Auml", "Ä"), Map.entry("Ouml", "Ö"),
            Map.entry("Uuml", "Ü"), Map.entry("szlig", "ß"), Map.entry("euro", "€"), Map.entry("ndash", "–"),
            Map.entry("mdash", "—"), Map.entry("bdquo", "„"), Map.entry("ldquo", "“"), Map.entry("rdquo", "”"),
            Map.entry("sbquo", "‚"), Map.entry("lsquo", "‘"), Map.entry("rsquo", "’"), Map.entry("hellip", "…"),
            Map.entry("bull", "•"), Map.entry("middot", "·"), Map.entry("eacute", "é"), Map.entry("egrave", "è"),
            Map.entry("agrave", "à"), Map.entry("copy", "©"), Map.entry("sect", "§"), Map.entry("deg", "°"),
            Map.entry("times", "×"), Map.entry("shy", ""));

    private static final java.util.regex.Pattern ENTITY = java.util.regex.Pattern
            .compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});");

    /** Decodes named (common) and numeric HTML entities in one pass, so "&amp;uuml;" stays "&uuml;". */
    static String decodeEntities(String s) {
        java.util.regex.Matcher m = ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String g = m.group(1);
            String rep = null;
            try {
                if (g.startsWith("#x") || g.startsWith("#X")) {
                    rep = new String(Character.toChars(Integer.parseInt(g.substring(2), 16)));
                } else if (g.startsWith("#")) {
                    rep = new String(Character.toChars(Integer.parseInt(g.substring(1))));
                } else if ("amp".equals(g)) {
                    rep = "&";
                } else {
                    rep = ENTITIES.get(g);
                }
            } catch (IllegalArgumentException e) {
                rep = null;
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep == null ? m.group() : rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String str(JsonObject o, String key) {
        if (o == null || !o.has(key)) {
            return "";
        }
        JsonElement e = o.get(key);
        return e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static JsonObject obj(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
    }

    private static JsonArray arr(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isBoolean()
                && o.get(key).getAsBoolean();
    }

    private static String indexStr(JsonObject o, String key, int index) {
        JsonArray a = arr(o, key);
        if (index < a.size() && a.get(index).isJsonPrimitive()) {
            return a.get(index).getAsString();
        }
        return "";
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    public static LocalDate date(String s) {
        if (s == null || s.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static LocalTime time(String s) {
        if (s == null || s.length() < 5) {
            return null;
        }
        try {
            return LocalTime.parse(s.length() >= 8 ? s.substring(0, 8) : s.substring(0, 5));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static ZonedDateTime utc(String s, ZoneId zone) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s).atZoneSameInstant(zone);
        } catch (DateTimeParseException e) {
            try {
                // some instances omit the "Z" although the value is UTC
                return java.time.LocalDateTime.parse(s.length() > 19 ? s.substring(0, 19) : s).atOffset(ZoneOffset.UTC)
                        .atZoneSameInstant(zone);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}
