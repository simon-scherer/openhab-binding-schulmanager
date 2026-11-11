package org.openhab.core.types;
public interface Type { String format(String pattern); String toString(); default String toFullString(){return toString();} }
