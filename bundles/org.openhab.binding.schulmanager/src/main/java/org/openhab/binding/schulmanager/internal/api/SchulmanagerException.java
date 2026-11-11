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

/**
 * Error while talking to Schulmanager Online.
 *
 * Ported in parts from the MIT licensed Home Assistant integration
 * https://github.com/MrIcemanLE/Schulmanager-homeassistant
 *
 * @author Simon Scherer - Initial contribution
 */
@NonNullByDefault
public class SchulmanagerException extends Exception {

    private static final long serialVersionUID = 1L;

    public SchulmanagerException(String message) {
        super(message);
    }

    public SchulmanagerException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Schulmanager rejected the credentials (HTTP 401). */
    public static class AuthException extends SchulmanagerException {
        private static final long serialVersionUID = 1L;

        public AuthException(String message) {
            super(message);
        }
    }

    /** The account needs a two-factor code (also thrown for a wrong code). */
    public static class TwoFactorRequiredException extends SchulmanagerException {
        private static final long serialVersionUID = 1L;
        private final String method;

        public TwoFactorRequiredException(String method) {
            super("Two-factor code required (" + method + ")");
            this.method = method;
        }

        /** "totp" (authenticator app) or "email". */
        public String getMethod() {
            return method;
        }
    }

    /** The school has not enabled a portal module (HTTP 403 for that request). */
    public static class ModuleUnavailableException extends SchulmanagerException {
        private static final long serialVersionUID = 1L;

        public ModuleUnavailableException(String module) {
            super("Module not enabled by the school: " + module);
        }
    }
}
