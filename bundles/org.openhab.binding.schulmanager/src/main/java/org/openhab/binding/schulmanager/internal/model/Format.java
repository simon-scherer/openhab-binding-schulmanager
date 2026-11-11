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
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Date texts for the text channels. Schulmanager Online is only used in Germany and all its content is German, so the
 * texts derived from it are German as well.
 *
 * @author Simon Scherer - Initial contribution
 */
@NonNullByDefault
public final class Format {

    private static final Locale DE = Locale.GERMAN;
    private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("dd.MM.", DE);

    private Format() {
    }

    /** "Mo 10.11." */
    public static String shortDate(LocalDate d) {
        return d.getDayOfWeek().getDisplayName(TextStyle.SHORT, DE).replace(".", "") + " " + d.format(DM);
    }

    /** "Montag, 10.11." */
    public static String dayLabel(LocalDate d) {
        return d.getDayOfWeek().getDisplayName(TextStyle.FULL, DE) + ", " + d.format(DM);
    }
}
