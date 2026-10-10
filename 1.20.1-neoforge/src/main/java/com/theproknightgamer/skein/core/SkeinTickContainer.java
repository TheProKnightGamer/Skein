package com.theproknightgamer.skein.core;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ticks.ScheduledTick;

/**
 * State {@code LevelTicks} needs to take part in a parallel phase: the level it
 * belongs to, which it otherwise has no reference to, and the reusable split of
 * the ticks it is about to run.
 */
public interface SkeinTickContainer {

    void skein$bindLevel(ServerLevel level);

    ServerLevel skein$level();

    ClusterPartition<ScheduledTick<?>> skein$partition();
}
