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
package org.openhab.binding.schulmanager.internal.api;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * A child (or the account holder itself for student accounts) as returned by the login.
 *
 * @author Simon Scherer - Initial contribution
 */
@NonNullByDefault
public class StudentInfo {

    public final String id;
    public final String firstname;
    public final String lastname;
    public final @Nullable Integer classId;
    /** Set by the account handler: name of the school this student belongs to. */
    public String schoolName = "";

    public StudentInfo(String id, @Nullable String firstname, @Nullable String lastname, @Nullable Integer classId) {
        this.id = id;
        this.firstname = firstname == null ? "" : firstname;
        this.lastname = lastname == null ? "" : lastname;
        this.classId = classId;
    }

    public String getName() {
        String name = (firstname + " " + lastname).trim();
        return name.isEmpty() ? id : name;
    }
}
