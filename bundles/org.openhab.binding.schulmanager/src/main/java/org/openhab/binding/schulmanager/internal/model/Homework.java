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

/**
 * A homework entry from the class book (date of the lesson it was given in, subject, text).
 *
 * @author Simon Scherer - Initial contribution
 */
public class Homework {
    public LocalDate date;
    public String subject = "";
    /** Subject as delivered by Schulmanager (used for the key). */
    public String keySubject = "";
    public String text = "";

    /** Stable key to detect new entries. */
    public String key() {
        return date + "|" + (keySubject.isEmpty() ? subject : keySubject) + "|" + text;
    }
}
