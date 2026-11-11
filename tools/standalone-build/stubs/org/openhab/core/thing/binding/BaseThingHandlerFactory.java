package org.openhab.core.thing.binding;
import org.openhab.core.thing.*;
public abstract class BaseThingHandlerFactory implements ThingHandlerFactory { protected abstract ThingHandler createHandler(Thing thing); }
