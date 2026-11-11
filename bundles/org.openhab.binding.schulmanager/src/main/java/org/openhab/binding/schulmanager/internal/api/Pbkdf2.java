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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * PBKDF2-HMAC-SHA512 exactly as the Schulmanager web app computes it:
 * password as ISO-8859-1 bytes, salt as UTF-8, 99999 iterations, 512 byte key, hex output.
 *
 * The JDK's PBKDF2WithHmacSHA512 encodes the password as UTF-8, which would differ for
 * umlauts, so the algorithm is implemented directly on top of HmacSHA512.
 *
 * @author Simon Scherer - Initial contribution
 */
@NonNullByDefault
public final class Pbkdf2 {

    public static final int ITERATIONS = 99999;
    public static final int KEY_LENGTH = 512;

    private Pbkdf2() {
    }

    public static String hashHex(String password, String salt) throws GeneralSecurityException {
        byte[] pw = password.getBytes(StandardCharsets.ISO_8859_1);
        byte[] saltBytes = salt.getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(derive(pw, saltBytes, ITERATIONS, KEY_LENGTH));
    }

    static byte[] derive(byte[] password, byte[] salt, int iterations, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA512");
        // An empty key is not allowed by SecretKeySpec; HMAC pads it to zeros anyway
        mac.init(new SecretKeySpec(password.length == 0 ? new byte[1] : password, "HmacSHA512"));
        int hLen = mac.getMacLength();
        int blocks = (length + hLen - 1) / hLen;
        byte[] out = new byte[length];
        byte[] u = new byte[hLen];
        byte[] t = new byte[hLen];
        for (int i = 1; i <= blocks; i++) {
            mac.update(salt);
            mac.update(new byte[] { (byte) (i >>> 24), (byte) (i >>> 16), (byte) (i >>> 8), (byte) i });
            mac.doFinal(u, 0);
            System.arraycopy(u, 0, t, 0, hLen);
            for (int j = 1; j < iterations; j++) {
                mac.update(u);
                mac.doFinal(u, 0);
                for (int k = 0; k < hLen; k++) {
                    t[k] ^= u[k];
                }
            }
            int offset = (i - 1) * hLen;
            System.arraycopy(t, 0, out, offset, Math.min(hLen, length - offset));
        }
        return out;
    }
}
