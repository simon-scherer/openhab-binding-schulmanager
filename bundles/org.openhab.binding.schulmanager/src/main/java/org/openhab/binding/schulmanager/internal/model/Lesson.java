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
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * One timetable entry as it actually takes place (format follows the "schema_version 1" export of the Home
 * Assistant integration).
 *
 * @author Simon Scherer - Initial contribution
 */
@NonNullByDefault
public class Lesson {

    public static final String TEACHER_ABBREVIATION = "abbreviation";
    public static final String TEACHER_LASTNAME = "lastname";
    public static final String TEACHER_NAME = "name";
    public static final String SUBJECT_ABBREVIATION = "abbreviation";
    public static final String SUBJECT_NAME = "name";

    public static final String STATUS_SCHEDULED = "scheduled";
    public static final String STATUS_CHANGED = "changed";
    public static final String STATUS_CANCELLED = "cancelled";

    public String id = "";
    public @Nullable LocalDate date;
    public String period = "";
    public @Nullable LocalTime start;
    public @Nullable LocalTime end;
    public String subject = "";
    public String subjectFull = "";
    /** Subject as configured for display (subjectDisplay). */
    public String shownSubject = "";
    public @Nullable String group;
    /** Teacher abbreviations (key for change detection). */
    public List<String> teachers = new ArrayList<>();
    /** Full names "Vorname Nachname" (abbreviation if the school sends no name), same order as teachers. */
    public List<String> teacherNames = new ArrayList<>();
    public List<String> teacherLastnames = new ArrayList<>();
    /** Teachers as configured for display (teacherDisplay). */
    public List<String> shownTeachers = new ArrayList<>();
    public List<String> rooms = new ArrayList<>();
    /** lesson, event or unknown */
    public String kind = "lesson";
    /** scheduled, changed, cancelled */
    public String status = STATUS_SCHEDULED;
    /** cancellation, subject, teacher, room, special */
    public List<String> changes = new ArrayList<>();
    public List<String> notes = new ArrayList<>();
    public @Nullable Original original;
    public String sourceType = "";

    public static class Original {
        public String subject = "";
        public String subjectFull = "";
        public List<String> teachers = new ArrayList<>();
        public List<String> teacherNames = new ArrayList<>();
        public List<String> teacherLastnames = new ArrayList<>();
        public List<String> shownTeachers = new ArrayList<>();
        public String shownSubject = "";
        public List<String> rooms = new ArrayList<>();
        public @Nullable String group;
    }

    public void applyDisplay(String teacherMode, String subjectMode) {
        applyDisplay(teacherMode, subjectMode, SubjectNames.NONE);
    }

    public void applyDisplay(String teacherMode, String subjectMode, SubjectNames names) {
        applyTeacherDisplay(teacherMode);
        String own = names.lookup(subject, subjectFull);
        shownSubject = own != null ? own : subjectText(subjectMode, subject, subjectFull);
        Original o = original;
        if (o != null) {
            String ownOrig = names.lookup(o.subject, o.subjectFull);
            o.shownSubject = ownOrig != null ? ownOrig : subjectText(subjectMode, o.subject, o.subjectFull);
        }
    }

    private static String subjectText(String mode, String abbr, String full) {
        if (SUBJECT_NAME.equals(mode)) {
            return full.isEmpty() ? abbr : full;
        }
        return abbr.isEmpty() ? full : abbr;
    }

    public void applyTeacherDisplay(String mode) {
        shownTeachers = pick(mode, teachers, teacherNames, teacherLastnames);
        Original o = original;
        if (o != null) {
            o.shownTeachers = pick(mode, o.teachers, o.teacherNames, o.teacherLastnames);
        }
    }

    private static List<String> pick(String mode, List<String> abbr, List<String> names, List<String> last) {
        List<String> chosen = TEACHER_NAME.equals(mode) ? names : TEACHER_LASTNAME.equals(mode) ? last : abbr;
        return new ArrayList<>(chosen.size() == abbr.size() ? chosen : abbr);
    }

    /** "Anna Becker (Bec)" for each teacher; just the abbreviation if no name is known. */
    public static String fullTeacherText(List<String> abbr, List<String> names) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < abbr.size(); i++) {
            String n = i < names.size() ? names.get(i) : abbr.get(i);
            parts.add(n.equals(abbr.get(i)) ? n : n + " (" + abbr.get(i) + ")");
        }
        return String.join(", ", parts);
    }

    public boolean isCancelled() {
        return STATUS_CANCELLED.equals(status);
    }

    public boolean isChanged() {
        return !STATUS_SCHEDULED.equals(status) || !changes.isEmpty();
    }

    /** Short German description of the change, e.g. "Entfall", "Vertretung (Hof)", "Raum 204". */
    public String describeChange() {
        if (isCancelled()) {
            return "Entfall";
        }
        List<String> parts = new ArrayList<>();
        if (changes.contains("special")) {
            parts.add("Sonderstunde");
        }
        Original o = original;
        if (changes.contains("subject") && o != null) {
            parts.add("statt " + (o.shownSubject.isEmpty() ? o.subject : o.shownSubject));
        }
        if (changes.contains("teacher")) {
            parts.add("Vertretung" + (shownTeachers.isEmpty() ? "" : " (" + String.join(", ", shownTeachers) + ")"));
        }
        if (changes.contains("room")) {
            parts.add("Raum " + String.join(", ", rooms));
        }
        if (parts.isEmpty() && !STATUS_SCHEDULED.equals(status)) {
            parts.add("Geändert");
        }
        return String.join(", ", parts);
    }

    /** One line, e.g. "3. Std 09:45 M (Bec) R 204 – Vertretung (Hof)". */
    public String toLine() {
        StringBuilder sb = new StringBuilder();
        if (!period.isEmpty()) {
            sb.append(period).append(". Std");
        }
        LocalTime s = start;
        if (s != null) {
            sb.append(sb.length() > 0 ? " " : "").append(s.toString());
        }
        String subj = !shownSubject.isEmpty() ? shownSubject : subject.isEmpty() ? subjectFull : subject;
        sb.append(sb.length() > 0 ? " " : "").append(subj);
        if (!shownTeachers.isEmpty()) {
            sb.append(" (").append(String.join(", ", shownTeachers)).append(")");
        }
        if (!rooms.isEmpty()) {
            sb.append(" R ").append(String.join(", ", rooms));
        }
        String change = describeChange();
        if (!change.isEmpty()) {
            sb.append(" – ").append(change);
        }
        if (!notes.isEmpty()) {
            sb.append(" – ").append(String.join("; ", notes));
        }
        return sb.toString();
    }
}
