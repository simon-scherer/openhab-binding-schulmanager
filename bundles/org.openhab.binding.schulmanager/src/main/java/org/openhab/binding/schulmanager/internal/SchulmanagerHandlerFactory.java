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

import static org.openhab.binding.schulmanager.internal.SchulmanagerBindingConstants.*;

import java.net.http.HttpClient;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.schulmanager.internal.api.SchulmanagerClient;
import org.openhab.binding.schulmanager.internal.handler.SchulmanagerAccountHandler;
import org.openhab.binding.schulmanager.internal.handler.SchulmanagerStudentHandler;
import org.openhab.core.i18n.TimeZoneProvider;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Creates the handlers of the Schulmanager binding.
 *
 * @author Simon Scherer - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = "binding.schulmanager", service = ThingHandlerFactory.class)
public class SchulmanagerHandlerFactory extends BaseThingHandlerFactory {

    /** One HTTP client shared by all accounts of this binding. */
    private final HttpClient httpClient = SchulmanagerClient.createHttpClient();
    private final TimeZoneProvider timeZoneProvider;

    @Activate
    public SchulmanagerHandlerFactory(@Reference TimeZoneProvider timeZoneProvider) {
        this.timeZoneProvider = timeZoneProvider;
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID type = thing.getThingTypeUID();
        if (THING_TYPE_ACCOUNT.equals(type)) {
            return new SchulmanagerAccountHandler((Bridge) thing, httpClient, timeZoneProvider::getTimeZone);
        }
        if (THING_TYPE_STUDENT.equals(type)) {
            return new SchulmanagerStudentHandler(thing, timeZoneProvider::getTimeZone);
        }
        return null;
    }
}
