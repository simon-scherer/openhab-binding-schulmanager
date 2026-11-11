package org.openhab.core.thing.binding;
import org.openhab.core.thing.*; import org.openhab.core.types.*;
public abstract class BaseThingHandler implements ThingHandler {
 protected final java.util.concurrent.ScheduledExecutorService scheduler = null; protected Thing thing;
 public BaseThingHandler(Thing t){}
 public Thing getThing(){return null;} public void dispose(){} public void handleRemoval(){} public void bridgeStatusChanged(ThingStatusInfo i){}
 protected <T> T getConfigAs(Class<T> c){return null;}
 protected void updateState(String c, State s){} protected void updateState(ChannelUID c, State s){}
 protected void triggerChannel(String c, String e){}
 protected void updateStatus(ThingStatus s, ThingStatusDetail d, String m){} protected void updateStatus(ThingStatus s, ThingStatusDetail d){} protected void updateStatus(ThingStatus s){}
 protected void updateProperty(String n, String v){} protected void updateProperties(java.util.Map<String,String> p){}
 protected Bridge getBridge(){return null;} protected boolean isLinked(String c){return false;} }
