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
package org.openhab.binding.schulmanager.internal.config;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Configuration of the account bridge.
 *
 * @author Simon Scherer - Initial contribution
 */
@NonNullByDefault
public class AccountConfiguration {
    public String username = "";
    public String password = "";
    /** One-time code of the authenticator app or e-mail, only needed until the device is remembered. */
    public String twoFactorCode = "";
    /** Minutes between two data refreshes. */
    public int refreshInterval = 60;
    /** Number of weeks of timetable (current week + following weeks, 1-3). */
    public int scheduleWeeks = 2;
    /** How teachers are shown in the timetable: abbreviation, lastname or name. */
    public String teacherDisplay = "lastname";
    /** How subjects are shown: name (e.g. Geographie) or abbreviation (Geo). */
    public String subjectDisplay = "name";
    /** Own names per subject, e.g. "Ev=Religion, Swd=Sport" (key = abbreviation or official name). */
    public String subjectNames = "";
    /**
     * Also load the text of unread parent letters. Off by default: it is not verified that the app's letter call
     * leaves an unread letter unread.
     */
    public boolean lettersLoadUnread = false;
    /** Days ahead for exams and school events. */
    public int examDaysAhead = 180;
}
