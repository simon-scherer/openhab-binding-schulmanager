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
package org.openhab.binding.schulmanager.internal.handler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.OpenHAB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Keeps the remembered two-factor devices of an account in $OPENHAB_USERDATA/schulmanager, so they survive
 * restarts also for things defined in .things files (whose configuration the binding cannot change).
 *
 * @author Simon Scherer - Initial contribution
 */
@NonNullByDefault
public class DeviceStore {

    private final Logger logger = LoggerFactory.getLogger(DeviceStore.class);
    private final Path file;

    public DeviceStore(String thingUid) {
        this.file = Path.of(OpenHAB.getUserDataFolder(), "schulmanager",
                thingUid.replaceAll("[^A-Za-z0-9_.-]", "_") + ".json");
    }

    public synchronized List<JsonObject> load() {
        List<JsonObject> list = new ArrayList<>();
        if (!Files.exists(file)) {
            return list;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (root.isJsonObject() && root.getAsJsonObject().has("devices")) {
                for (JsonElement e : root.getAsJsonObject().getAsJsonArray("devices")) {
                    if (e.isJsonObject()) {
                        list.add(e.getAsJsonObject());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.warn("Cannot read the remembered two-factor devices ({}): {}", file, e.getMessage());
        }
        return list;
    }

    public synchronized void save(List<JsonObject> devices) {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        devices.forEach(arr::add);
        root.add("devices", arr);
        try {
            Path dir = file.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.warn("Cannot save the remembered two-factor device ({}): {}", file, e.getMessage());
        }
    }

    public synchronized void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            logger.debug("Cannot delete {}: {}", file, e.getMessage());
        }
    }
}
