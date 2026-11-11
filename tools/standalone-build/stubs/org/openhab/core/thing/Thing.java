package org.openhab.core.thing;
import org.openhab.core.thing.binding.ThingHandler;
public interface Thing { ThingUID getUID(); ThingTypeUID getThingTypeUID(); ThingUID getBridgeUID(); String getLabel(); ThingHandler getHandler(); ThingStatus getStatus(); org.openhab.core.config.core.Configuration getConfiguration(); }
