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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * A class exam / test (Schulaufgabe, Stegreifaufgabe, Test ...) or a school-wide calendar event.
 *
 * @author Simon Scherer - Initial contribution
 */
@NonNullByDefault
public class Exam {
    public String id = "";
    public LocalDate date = LocalDate.MIN;
    /** e.g. "Schulaufgabe", "Test", for calendar events "Schultermin" */
    public String type = "";
    public String subject = "";
    public String subjectShort = "";
    public String comment = "";
    public String period = "";
    public @Nullable LocalTime start;
    public @Nullable LocalTime end;
    public String color = "";
    /** true for school-wide calendar events (Schultermine) */
    public boolean schoolEvent;
    /** last day of a multi-day all-day event (e.g. holidays), otherwise null */
    public @Nullable LocalDate endDate;
    public String location = "";
    /** organizer as delivered (teacher abbreviations like "Sn/Rd") */
    public String organizer = "";
    public String category = "";
    public String categoryColor = "";
    public boolean holiday;

    /** Last day this entry concerns (end of a multi-day event, otherwise its date). */
    public LocalDate lastDay() {
        LocalDate e = endDate;
        return e != null && e.isAfter(date) ? e : date;
    }

    /** e.g. "Schulaufgabe Mathematik" or the event title. */
    public String title() {
        if (schoolEvent) {
            return subject;
        }
        String t = (type + " " + subject).trim();
        return t.isEmpty() ? "Prüfung" : t;
    }
}
