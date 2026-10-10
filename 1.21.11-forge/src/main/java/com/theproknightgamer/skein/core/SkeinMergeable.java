package com.theproknightgamer.skein.core;

/**
 * Whether an entity can merge with another of its kind this tick -- vanilla's
 * own test, for the item it is implemented on. See {@code ClusterPass#reachOf}.
 */
public interface SkeinMergeable {
    boolean skein$mayMerge();
}
