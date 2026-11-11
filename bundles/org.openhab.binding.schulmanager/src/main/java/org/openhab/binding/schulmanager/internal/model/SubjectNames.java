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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * User defined display names per subject, configured as {@code "Ev=Religion, Swd=Sport"}.
 * The key may be the subject abbreviation (Ev) or the official name (Evangelische Religionslehre), case does not
 * matter. Entries are separated by comma, semicolon or line break.
 *
 * @author Simon Scherer - Initial contribution
 */
public final class SubjectNames {

    public static final SubjectNames NONE = new SubjectNames(Map.of());

    private final Map<String, String> names;
    /** official name -> abbreviation, learned from the timetable (homework often carries only the name) */
    private final Map<String, String> abbreviations = new ConcurrentHashMap<>();

    private SubjectNames(Map<String, String> names) {
        this.names = names;
    }

    /** Remember which abbreviation belongs to an official subject name. */
    public void learn(String abbreviation, String fullName) {
        if (names.isEmpty() || abbreviation == null || abbreviation.isEmpty() || fullName == null
                || fullName.isEmpty()) {
            return;
        }
        abbreviations.put(fullName.trim().toLowerCase(Locale.ROOT), abbreviation.trim());
    }

    public static SubjectNames parse(String config) {
        if (config == null || config.isBlank()) {
            return NONE;
        }
        Map<String, String> map = new HashMap<>();
        for (String entry : config.split("[,;\\n]")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = entry.substring(0, eq).trim();
            String value = entry.substring(eq + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                map.put(key.toLowerCase(Locale.ROOT), value);
            }
        }
        return map.isEmpty() ? NONE : new SubjectNames(map);
    }

    /** The configured name for this subject (abbreviation is checked first), or null. */
    public String lookup(String abbreviation, String fullName) {
        if (names.isEmpty()) {
            return null;
        }
        String v = abbreviation == null || abbreviation.isEmpty() ? null
                : names.get(abbreviation.trim().toLowerCase(Locale.ROOT));
        if (v == null && fullName != null && !fullName.isEmpty()) {
            String key = fullName.trim().toLowerCase(Locale.ROOT);
            v = names.get(key);
            if (v == null && abbreviations.containsKey(key)) {
                v = names.get(abbreviations.get(key).toLowerCase(Locale.ROOT));
            }
        }
        return v;
    }

    public int size() {
        return names.size();
    }
}
