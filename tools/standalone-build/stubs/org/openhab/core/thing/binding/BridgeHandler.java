package org.openhab.core.thing.binding;
import org.openhab.core.thing.*;
public interface BridgeHandler extends ThingHandler { void childHandlerInitialized(ThingHandler h, Thing t); void childHandlerDisposed(ThingHandler h, Thing t); }
