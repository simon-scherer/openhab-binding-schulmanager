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

import java.time.LocalTime;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * A class hour of the school (default times, Monday to Friday).
 *
 * @author Simon Scherer - Initial contribution
 */
@NonNullByDefault
public class Period {
    public String number = "";
    public @Nullable LocalTime start;
    public @Nullable LocalTime end;
}
