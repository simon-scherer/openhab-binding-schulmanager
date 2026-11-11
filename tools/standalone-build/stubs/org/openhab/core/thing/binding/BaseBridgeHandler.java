package org.openhab.core.thing.binding;
import org.openhab.core.thing.*;
public abstract class BaseBridgeHandler extends BaseThingHandler implements BridgeHandler {
 public BaseBridgeHandler(Bridge b){super(b);} public Bridge getThing(){return null;}
 public void childHandlerInitialized(ThingHandler h, Thing t){} public void childHandlerDisposed(ThingHandler h, Thing t){} }
