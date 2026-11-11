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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.schulmanager.internal.model.DataParser.Schedule;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Display-ready JSON for the openHAB Main UI widgets. Everything the widgets show is prepared here (texts, colours
 * as status codes, "now"/"today" flags), so the widget expressions only have to place the values.
 *
 * The JSON changes when the data changes, when the current lesson changes and once a day (relative texts).
 *
 * @author Simon Scherer - Initial contribution
 */
@NonNullByDefault
public final class WidgetJson {

    private static final Locale DE = Locale.GERMAN;
    private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("dd.MM.", DE);

    private WidgetJson() {
    }

    // ================================================================== timetable

    /**
     * <pre>
     * {v, defaultWeek, weeks:[{label, range, changes, cols,
     *     days:[{wd, dm, label, today, lessons:[L]}],
     *     rows:[{p, from, to, cells:[[L]]}]}]}
     * L = {id, s, f, m, c, n, o, g, t, st (ok|chg|sp|x), now}
     * </pre>
     */
    public static JsonObject schedule(Schedule s, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        LocalTime t = now.toLocalTime();
        JsonObject root = new JsonObject();
        root.addProperty("v", 1);

        Map<LocalDate, List<LocalDate>> weeks = new LinkedHashMap<>();
        for (LocalDate d : s.days.keySet()) {
            LocalDate monday = d.minusDays(d.getDayOfWeek().getValue() - 1L);
            List<LocalDate> days = weeks.computeIfAbsent(monday, k -> new ArrayList<>());
            if (d.getDayOfWeek().getValue() < 6 || !s.lessonsOn(d).isEmpty()) {
                days.add(d);
            }
        }
        LocalDate thisMonday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        LocalDate defaultMonday = today.getDayOfWeek().getValue() >= 6 ? thisMonday.plusWeeks(1) : thisMonday;

        // all periods of the school (+ periods only appearing in lessons)
        TreeMap<Integer, String[]> numbered = new TreeMap<>();
        List<String[]> other = new ArrayList<>();
        for (Period p : s.periods) {
            addRow(numbered, other, p.number, p.start, p.end);
        }
        for (List<Lesson> ls : s.days.values()) {
            for (Lesson l : ls) {
                addRow(numbered, other, l.period, l.start, l.end);
            }
        }
        List<String[]> allRows = new ArrayList<>(numbered.values());
        allRows.addAll(other);

        JsonArray weeksJson = new JsonArray();
        int index = 0;
        int defaultIndex = 0;
        for (Map.Entry<LocalDate, List<LocalDate>> w : weeks.entrySet()) {
            LocalDate monday = w.getKey();
            List<LocalDate> days = w.getValue();
            if (monday.equals(defaultMonday)) {
                defaultIndex = index;
            }
            JsonObject wj = new JsonObject();
            wj.addProperty("k", monday.toString());
            String label = monday.equals(thisMonday) ? "Diese Woche"
                    : monday.equals(thisMonday.plusWeeks(1)) ? "Nächste Woche"
                            : "KW " + monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            wj.addProperty("label", label);
            wj.addProperty("range", monday.format(DM) + "–" + monday.plusDays(4).format(DM));
            wj.addProperty("cols", days.size());
            int changes = 0;

            JsonArray daysJson = new JsonArray();
            int lastPeriod = 0;
            for (LocalDate d : days) {
                JsonObject dj = new JsonObject();
                dj.addProperty("k", d.toString());
                dj.addProperty("wd", weekday(d));
                dj.addProperty("dm", d.format(DM));
                dj.addProperty("label", dayLabel(d));
                dj.addProperty("today", d.equals(today));
                JsonArray lessons = new JsonArray();
                for (Lesson l : s.lessonsOn(d)) {
                    lessons.add(lesson(l, d.equals(today) && isNow(l, t)));
                    if (l.isChanged()) {
                        changes++;
                    }
                    lastPeriod = Math.max(lastPeriod, number(l.period));
                }
                dj.add("lessons", lessons);
                if (lessons.isEmpty()) {
                    dj.addProperty("empty", "Kein Unterricht");
                }
                daysJson.add(dj);
            }
            wj.addProperty("changes", changes);
            wj.add("days", daysJson);

            JsonArray rows = new JsonArray();
            for (String[] row : allRows) {
                boolean used = days.stream()
                        .anyMatch(d -> s.lessonsOn(d).stream().anyMatch(l -> row[0].equals(l.period)));
                int n = number(row[0]);
                if (!used && (n == Integer.MAX_VALUE || n > lastPeriod)) {
                    continue; // trailing periods without lessons
                }
                JsonObject rj = new JsonObject();
                rj.addProperty("k", "p" + row[0]);
                rj.addProperty("p", row[0]);
                rj.addProperty("from", row[1]);
                rj.addProperty("to", row[2]);
                JsonArray cells = new JsonArray();
                for (LocalDate d : days) {
                    JsonObject cell = new JsonObject();
                    cell.addProperty("k", d + "/" + row[0]);
                    cell.addProperty("today", d.equals(today));
                    JsonArray inCell = new JsonArray();
                    for (Lesson l : s.lessonsOn(d)) {
                        if (row[0].equals(l.period)) {
                            inCell.add(lesson(l, d.equals(today) && isNow(l, t)));
                        }
                    }
                    cell.add("l", inCell);
                    cells.add(cell);
                }
                rj.add("cells", cells);
                rows.add(rj);
            }
            wj.add("rows", rows);
            weeksJson.add(wj);
            index++;
        }
        root.addProperty("defaultWeek", defaultIndex);
        root.add("weeks", weeksJson);
        return root;
    }

    private static boolean isNow(Lesson l, LocalTime t) {
        LocalTime start = l.start;
        LocalTime end = l.end;
        return !l.isCancelled() && start != null && end != null && !t.isBefore(start) && t.isBefore(end);
    }

    private static JsonObject lesson(Lesson l, boolean now) {
        JsonObject o = new JsonObject();
        o.addProperty("id", l.id);
        String full = l.subjectFull.isEmpty() ? l.subject : l.subjectFull;
        o.addProperty("s", !l.shownSubject.isEmpty() ? l.shownSubject : l.subject.isEmpty() ? full : l.subject);
        o.addProperty("f", full);
        List<String> meta = new ArrayList<>();
        if (!l.shownTeachers.isEmpty()) {
            meta.add(String.join(", ", l.shownTeachers));
        }
        if (!l.rooms.isEmpty()) {
            meta.add(String.join(", ", l.rooms));
        }
        o.addProperty("m", String.join(" · ", meta));
        o.addProperty("c", l.describeChange());
        o.addProperty("n", String.join(" · ", l.notes));
        // full teacher names for the detail view
        o.addProperty("tn", Lesson.fullTeacherText(l.teachers, l.teacherNames));
        o.addProperty("r", String.join(", ", l.rooms));
        // technical course group names (e.g. "Sp_6B6C_Sp/6BF_1") are not helpful; keep plain class names
        String group = l.group;
        o.addProperty("g", group == null || group.contains("_") ? "" : group);
        o.addProperty("p", l.period);
        LocalTime start = l.start;
        LocalTime end = l.end;
        o.addProperty("t", start == null ? "" : start + (end == null ? "" : "–" + end));
        String orig = "";
        Lesson.Original original = l.original;
        if (original != null && l.isChanged() && !l.isCancelled()) {
            List<String> parts = new ArrayList<>();
            parts.add(original.subjectFull.isEmpty() ? original.subject : original.subjectFull);
            if (!original.teachers.isEmpty()) {
                parts.add(Lesson.fullTeacherText(original.teachers, original.teacherNames));
            }
            if (!original.rooms.isEmpty()) {
                parts.add(String.join(", ", original.rooms));
            }
            orig = String.join(" · ", parts);
        }
        o.addProperty("o", orig);
        o.addProperty("st",
                l.isCancelled() ? "x" : l.changes.contains("special") ? "sp" : l.isChanged() ? "chg" : "ok");
        o.addProperty("now", now);
        return o;
    }

    private static void addRow(TreeMap<Integer, String[]> numbered, List<String[]> other, @Nullable String period,
            @Nullable LocalTime start, @Nullable LocalTime end) {
        String p = period == null ? "" : period;
        String[] row = { p, start == null ? "" : start.toString(), end == null ? "" : end.toString() };
        int n = number(p);
        if (n != Integer.MAX_VALUE) {
            numbered.putIfAbsent(n, row);
        } else if (other.stream().noneMatch(r -> r[0].equals(p))) {
            other.add(row);
        }
    }

    private static int number(@Nullable String p) {
        if (p == null || p.isEmpty() || p.length() > 3 || !p.chars().allMatch(Character::isDigit)) {
            return Integer.MAX_VALUE;
        }
        return Integer.parseInt(p);
    }

    // ================================================================== exams

    /**
     * <pre>
     * {v, exams:[{wd, dm, type, color, s, when, lvl (soon|near|""), p, n}], events:[{wd, dm, s, time, n}]}
     * </pre>
     */
    public static JsonObject exams(List<Exam> exams, LocalDate today) {
        JsonObject root = new JsonObject();
        root.addProperty("v", 1);
        JsonArray ex = new JsonArray();
        JsonArray ev = new JsonArray();
        for (Exam x : exams) {
            if (x.lastDay().isBefore(today)) {
                continue;
            }
            long days = ChronoUnit.DAYS.between(today, x.date);
            JsonObject o = new JsonObject();
            o.addProperty("k", x.id + "/" + x.date + "/" + (ex.size() + ev.size()));
            o.addProperty("wd", weekday(x.date));
            o.addProperty("dm", x.date.format(DM));
            o.addProperty("n", x.comment);
            if (x.schoolEvent) {
                if (ev.size() >= 20) {
                    continue;
                }
                o.addProperty("s", x.subject);
                String time;
                LocalDate endDate = x.endDate;
                LocalTime start = x.start;
                LocalTime end = x.end;
                if (endDate != null && endDate.isAfter(x.date)) {
                    time = "bis " + weekday(endDate) + " " + endDate.format(DM);
                } else if (start == null) {
                    time = "ganztägig";
                } else {
                    time = start + (end != null && !end.equals(start) ? "–" + end : "") + " Uhr";
                }
                o.addProperty("time", time);
                o.addProperty("loc", x.location);
                o.addProperty("org", x.organizer);
                o.addProperty("cat", x.category);
                String cc = x.categoryColor.matches("#[0-9a-fA-F]{3}|#[0-9a-fA-F]{6}") ? x.categoryColor
                        : x.holiday ? "#3a3a3c" : "";
                o.addProperty("cc", cc);
                o.addProperty("ctc", cc.isEmpty() || luminance(cc) < 0.215 ? "#ffffff" : "#1d232b");
                o.addProperty("hol", x.holiday);
                ev.add(o);
            } else {
                o.addProperty("type", x.type);
                String color = x.color.matches("#[0-9a-fA-F]{3}|#[0-9a-fA-F]{6}") ? x.color : "";
                o.addProperty("color", color);
                // readable text on the school's tag colour (light colours get dark text)
                o.addProperty("tc", color.isEmpty() || luminance(color) < 0.215 ? "#ffffff" : "#1d232b");
                o.addProperty("s", x.subject);
                o.addProperty("when", days == 0 ? "heute" : days == 1 ? "morgen" : "in " + days + " Tagen");
                o.addProperty("lvl", days <= 2 ? "soon" : days <= 7 ? "near" : "");
                o.addProperty("p", !x.period.isEmpty() && !"X".equals(x.period) ? x.period + ". Stunde" : "");
                ex.add(o);
            }
        }
        root.add("exams", ex);
        root.add("events", ev);
        return root;
    }

    // ================================================================== homework

    /**
     * <pre>
     * {v, days:[{label, rel, fresh, items:[{s, t}]}]}  (last 21 days, newest first)
     * </pre>
     */
    public static JsonObject homework(List<Homework> homework, LocalDate today) {
        JsonObject root = new JsonObject();
        root.addProperty("v", 1);
        Map<LocalDate, JsonArray> byDay = new LinkedHashMap<>();
        for (Homework h : homework) {
            LocalDate date = h.date;
            if (date == null || date.isBefore(today.minusDays(21))) {
                continue;
            }
            JsonObject o = new JsonObject();
            JsonArray items = byDay.computeIfAbsent(date, k -> new JsonArray());
            o.addProperty("k", date + "/" + items.size());
            o.addProperty("s", h.subject);
            o.addProperty("t", h.text);
            items.add(o);
        }
        JsonArray days = new JsonArray();
        for (Map.Entry<LocalDate, JsonArray> e : byDay.entrySet()) {
            long ago = ChronoUnit.DAYS.between(e.getKey(), today);
            JsonObject d = new JsonObject();
            d.addProperty("k", e.getKey().toString());
            d.addProperty("label", dayLabel(e.getKey()));
            d.addProperty("rel", ago == 0 ? "heute" : ago == 1 ? "gestern" : ago < 0 ? "" : "vor " + ago + " Tagen");
            d.addProperty("fresh", ago <= 1);
            d.add("items", e.getValue());
            days.add(d);
        }
        root.add("days", days);
        return root;
    }

    // ================================================================== helpers

    /** Relative luminance (0 = black, 1 = white) of #rgb or #rrggbb. */
    static double luminance(String hex) {
        String h = hex.substring(1);
        if (h.length() == 3) {
            h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        }
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16) / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    private static String weekday(LocalDate d) {
        return d.getDayOfWeek().getDisplayName(TextStyle.SHORT, DE).replace(".", "");
    }

    private static String dayLabel(LocalDate d) {
        return d.getDayOfWeek().getDisplayName(TextStyle.FULL, DE) + ", " + d.format(DM);
    }
}
