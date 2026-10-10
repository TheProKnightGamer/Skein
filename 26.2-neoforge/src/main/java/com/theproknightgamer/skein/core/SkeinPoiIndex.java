package com.theproknightgamer.skein.core;

import java.util.concurrent.locks.ReentrantLock;

/**
 * The lock the point-of-interest index is read and written under, hung off
 * {@code SectionStorage} so that {@code PoiManager} and its base class share
 * one.
 *
 * <p>{@code PoiManager} is the only {@code SectionStorage} there is, but its
 * state is split across the two classes -- the section map and the dirty set
 * below, the village distance graph above -- and both halves are reached from
 * the same villager, golem, raider and bee ticks. One lock per manager, taken
 * on both sides, is what makes them one structure again.
 */
public interface SkeinPoiIndex {

    ReentrantLock skein$poiLock();
}
