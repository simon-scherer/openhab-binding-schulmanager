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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Parent letters (Elternbriefe) for the widget: list from letters/get-letters plus body text and attachment
 * names from the detail read. Nothing here changes the read state in Schulmanager.
 *
 * @author Simon Scherer - Initial contribution
 */
public final class Letters {

    public static final int MAX_LETTERS = 25;
    private static final int MAX_TEXT = 5000;
    private static final Locale DE = Locale.GERMAN;
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd.MM.yyyy", DE);
    private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("dd.MM.", DE);
    private static final DateTimeFormatter DMYY = DateTimeFormatter.ofPattern("dd.MM.yy", DE);
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm", DE);

    /** One letter of the list. */
    public static class Letter {
        public String id = "";
        public String title = "";
        public ZonedDateTime sent;
        public ZonedDateTime deadline;
        public boolean unread;
        public boolean answered;
        public Set<String> studentIds = new LinkedHashSet<>();
    }

    private Letters() {
    }

    /** Parses get-letters (all entries, newest first). */
    public static List<Letter> parseList(JsonArray data, ZoneId zone) {
        List<Letter> list = new ArrayList<>();
        for (JsonElement e : data) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            Letter l = new Letter();
            l.id = DataParser.str(o, "id");
            l.title = DataParser.str(o, "title").trim();
            l.sent = ts(DataParser.str(o, "sentDate"), zone);
            if (l.sent == null) {
                l.sent = ts(DataParser.str(o, "createdAt"), zone);
            }
            l.deadline = ts(DataParser.str(o, "answerDeadline"), zone);
            boolean anyAnswer = false;
            JsonElement st = o.get("studentStatuses");
            if (st != null && st.isJsonArray()) {
                for (JsonElement s : st.getAsJsonArray()) {
                    if (!s.isJsonObject()) {
                        continue;
                    }
                    JsonObject so = s.getAsJsonObject();
                    String sid = DataParser.str(so, "studentId");
                    if (!sid.isEmpty()) {
                        l.studentIds.add(sid);
                    }
                    JsonElement rt = so.get("readTimestamp");
                    if (rt == null || rt.isJsonNull() || rt.getAsString().isEmpty()) {
                        l.unread = true;
                    }
                    JsonElement fd = so.get("formData");
                    if (fd != null && fd.isJsonObject() && fd.getAsJsonObject().size() > 0) {
                        anyAnswer = true;
                    }
                }
            }
            l.answered = anyAnswer;
            if (!l.id.isEmpty()) {
                list.add(l);
            }
        }
        list.sort(Comparator.comparing((Letter l) -> l.sent == null ? ZonedDateTime.now(zone).minusYears(50) : l.sent)
                .reversed());
        return list;
    }

    /**
     * <pre>
     * {v, unread, letters:[{k, id, t, wd, d, u, dl, dlx, who, txt, att:[..], pending}]}
     * </pre>
     *
     * @param details id -> letter detail (findByPk), may miss entries (shown as "Text wird geladen")
     * @param studentNames student id -> first name; names are only shown when the account has several children
     */
    public static JsonObject widget(List<Letter> letters, Map<String, JsonObject> details,
            Map<String, String> studentNames, LocalDate today) {
        JsonObject root = new JsonObject();
        root.addProperty("v", 1);
        root.addProperty("unread", letters.stream().filter(l -> l.unread).count());
        JsonArray arr = new JsonArray();
        for (Letter l : letters.subList(0, Math.min(MAX_LETTERS, letters.size()))) {
            JsonObject o = new JsonObject();
            o.addProperty("k", l.id);
            o.addProperty("id", l.id);
            o.addProperty("t", l.title.isEmpty() ? "Elternbrief" : l.title);
            o.addProperty("wd",
                    l.sent == null ? "" : l.sent.getDayOfWeek().getDisplayName(TextStyle.SHORT, DE).replace(".", ""));
            o.addProperty("d", l.sent == null ? "" : l.sent.format(DMY));
            o.addProperty("dm", l.sent == null ? "" : l.sent.format(DM));
            o.addProperty("dt", l.sent == null ? "" : l.sent.format(DMYY));
            o.addProperty("tm", l.sent == null ? "" : l.sent.format(HM));
            o.addProperty("u", l.unread);
            String dl = "";
            boolean overdue = false;
            if (l.deadline != null && !l.answered) {
                LocalDate dd = l.deadline.toLocalDate();
                overdue = dd.isBefore(today);
                dl = (overdue ? "Rückmeldefrist abgelaufen (" : "Rückmeldung bis ")
                        + dd.getDayOfWeek().getDisplayName(TextStyle.SHORT, DE).replace(".", "") + " " + dd.format(DM)
                        + (overdue ? ")" : "");
            }
            o.addProperty("dl", dl);
            o.addProperty("dlx", overdue);
            List<String> who = new ArrayList<>();
            if (studentNames.size() > 1) {
                for (String sid : l.studentIds) {
                    String n = studentNames.get(sid);
                    if (n != null && !who.contains(n)) {
                        who.add(n);
                    }
                }
            }
            o.addProperty("who", String.join(", ", who));
            JsonObject det = details.get(l.id);
            JsonArray att = new JsonArray();
            String txt = "";
            if (det != null) {
                if (det.has("letter") && det.get("letter").isJsonObject() && !det.has("text")) {
                    det = det.getAsJsonObject("letter");
                }
                String raw = "";
                for (String key : new String[] { "text", "body", "content", "html", "message" }) {
                    raw = DataParser.str(det, key);
                    if (!raw.isEmpty()) {
                        break;
                    }
                }
                txt = DataParser.plainText(raw);
                if (txt.length() > MAX_TEXT) {
                    txt = txt.substring(0, MAX_TEXT) + " …";
                }
                JsonElement a = det.has("attachments") ? det.get("attachments") : det.get("files");
                if (a != null && a.isJsonArray()) {
                    for (JsonElement ae : a.getAsJsonArray()) {
                        if (ae.isJsonObject() && !isTrue(ae.getAsJsonObject(), "inline")) {
                            String fn = attachmentName(ae.getAsJsonObject());
                            if (!fn.isEmpty() && !contains(att, fn)) {
                                att.add(fn);
                            }
                        }
                    }
                }
            }
            o.addProperty("txt", txt);
            o.addProperty("pending", det == null);
            o.addProperty("locked", det == null && l.unread);
            o.add("att", att);
            arr.add(o);
        }
        root.add("letters", arr);
        return root;
    }

    /**
     * get-letter-for-reading: {"id":..,"file":"[535,\"letters\",hash,key,mime,size,\"Name.pdf\"]"} - the file is a
     * JSON-encoded array whose last string is the file name. Older shapes use "filename" / "name".
     */
    static String attachmentName(JsonObject a) {
        for (String key : new String[] { "filename", "fileName", "name", "originalName" }) {
            String s = DataParser.str(a, key).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        JsonElement f = a.get("file");
        JsonArray parts = null;
        try {
            if (f != null && f.isJsonPrimitive()) {
                JsonElement p = com.google.gson.JsonParser.parseString(f.getAsString());
                if (p.isJsonArray()) {
                    parts = p.getAsJsonArray();
                }
            } else if (f != null && f.isJsonArray()) {
                parts = f.getAsJsonArray();
            } else if (f != null && f.isJsonObject()) {
                return attachmentName(f.getAsJsonObject());
            }
        } catch (RuntimeException e) {
            return "";
        }
        if (parts != null) {
            for (int i = parts.size() - 1; i >= 0; i--) {
                JsonElement p = parts.get(i);
                if (p.isJsonPrimitive() && p.getAsJsonPrimitive().isString() && p.getAsString().contains(".")) {
                    return p.getAsString().trim();
                }
            }
        }
        return "";
    }

    private static boolean contains(JsonArray arr, String s) {
        for (JsonElement e : arr) {
            if (s.equals(e.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTrue(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static ZonedDateTime ts(String s, ZoneId zone) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s).atZoneSameInstant(zone);
        } catch (DateTimeParseException e) {
            LocalDate d = DataParser.date(s);
            return d == null ? null : d.atStartOfDay(zone);
        }
    }
}
