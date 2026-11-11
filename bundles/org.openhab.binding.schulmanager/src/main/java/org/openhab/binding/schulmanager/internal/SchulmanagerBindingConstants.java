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
package org.openhab.binding.schulmanager.internal;

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * Constants of the Schulmanager binding.
 *
 * @author Simon Scherer - Initial contribution
 */
@NonNullByDefault
public final class SchulmanagerBindingConstants {

    public static final String BINDING_ID = "schulmanager";

    public static final ThingTypeUID THING_TYPE_ACCOUNT = new ThingTypeUID(BINDING_ID, "account");
    public static final ThingTypeUID THING_TYPE_STUDENT = new ThingTypeUID(BINDING_ID, "student");
    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Set.of(THING_TYPE_ACCOUNT, THING_TYPE_STUDENT);

    // account (bridge) channels
    public static final String CH_LETTERS_UNREAD = "lettersUnread";
    public static final String CH_LETTERS_LIST = "lettersList";
    public static final String CH_LETTERS_WIDGET = "lettersWidget";
    public static final String CH_MESSAGES_UNREAD = "messagesUnread";
    public static final String CH_LAST_UPDATE = "lastUpdate";

    // student channel groups
    public static final String GROUP_SCHEDULE = "schedule";
    public static final String GROUP_EXAMS = "exams";
    public static final String GROUP_HOMEWORK = "homework";
    public static final String GROUP_INFO = "info";

    // schedule
    public static final String CH_TODAY = "today";
    public static final String CH_TOMORROW = "tomorrow";
    public static final String CH_NEXT_DAY = "nextDay";
    public static final String CH_NEXT_DAY_DATE = "nextDayDate";
    public static final String CH_CURRENT_LESSON = "currentLesson";
    public static final String CH_NEXT_LESSON = "nextLesson";
    public static final String CH_CHANGES_TODAY = "changesToday";
    public static final String CH_CHANGES_TOMORROW = "changesTomorrow";
    public static final String CH_CHANGES_SUMMARY = "changesSummary";
    public static final String CH_FIRST_LESSON_TODAY = "firstLessonToday";
    public static final String CH_LAST_LESSON_TODAY = "lastLessonToday";
    public static final String CH_FIRST_LESSON_NEXT_DAY = "firstLessonNextDay";
    public static final String CH_SCHEDULE_JSON = "json";
    public static final String CH_SCHEDULE_CHANGED = "changed";

    // exams
    public static final String CH_EXAM_NEXT = "next";
    public static final String CH_EXAM_NEXT_DATE = "nextDate";
    public static final String CH_EXAM_DAYS_UNTIL = "daysUntilNext";
    public static final String CH_EXAM_COUNT_14 = "countNext14Days";
    public static final String CH_EXAM_LIST = "list";
    public static final String CH_EXAM_NEXT_EVENT = "nextSchoolEvent";
    public static final String CH_EXAM_NEXT_EVENT_DATE = "nextSchoolEventDate";
    public static final String CH_EXAM_JSON = "json";
    public static final String CH_EXAM_NEW = "new";

    // homework
    public static final String CH_HW_LATEST = "latest";
    public static final String CH_HW_LATEST_DATE = "latestDate";
    public static final String CH_HW_COUNT_RECENT = "countRecent";
    public static final String CH_HW_LIST = "list";
    public static final String CH_HW_JSON = "json";
    public static final String CH_HW_NEW = "new";

    // info
    public static final String CH_INFO_LAST_UPDATE = "lastUpdate";
    public static final String CH_INFO_SCHOOL = "school";

    // account (bridge) properties
    public static final String PROPERTY_STUDENTS = "students";
    public static final String PROPERTY_SCHOOLS = "schools";
    public static final String PROPERTY_TWO_FACTOR_DEVICE = "twoFactorDevice";

    // display-ready JSON for the Main UI widgets (in schedule, exams and homework)
    public static final String CH_WIDGET = "widget";

    private SchulmanagerBindingConstants() {
    }
}
