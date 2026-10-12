package com.theproknightgamer.skein.core;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.redstone.NeighborUpdater;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-level state Skein needs, hung off {@code Level} itself by
 * {@link com.theproknightgamer.skein.mixin.LevelMixin} rather than kept in a side map.
 *
 * <p>A {@code Map<Level, ...>} would be a hash lookup on paths that run per
 * entity per tick, and would keep unloaded levels alive until something
 * remembered to evict them. Duck-typing the field onto the object costs a
 * checkcast.
 */
public interface SkeinLevel {

    /**
     * The reusable splits of this level's work, one per parallel phase. Each is
     * only touched by the thread that owns the level, and only between the
     * barriers, so they are kept from tick to tick instead of rebuilt.
     */
    Partition<Entity> skein$entityPartition();

    Partition<LevelChunk> skein$chunkPartition();

    Partition<TickingBlockEntity> skein$blockEntityPartition();

    /** Block entities grouped by what they can reach, for ticking them in vanilla's order. */
    ClusterPartition<TickingBlockEntity> skein$blockEntityGroups();

    /**
     * The cluster tier's reusable state -- the conflict graph's arrays and its
     * slice buffers. Kept per level for the same reason the partitions are:
     * a steady-state tick should not allocate to work out what it already knew
     * last tick.
     */
    ClusterPass skein$clusterPass();

    /** Per-level record of what each phase costs each way; see {@link PhaseGovernor}. */
    PhaseGovernor skein$governor();

    /**
     * A neighbour updater private to the calling thread.
     *
     * <p>{@code CollectingNeighborUpdater} is a small state machine with a
     * recursion counter and a shared stack; two threads placing blocks in one
     * level would interleave their update chains through it. Handing each
     * thread its own instance keeps the chains separate, at the cost of the
     * chain-depth limit being per-thread rather than per-level.
     */
    NeighborUpdater skein$neighborUpdater();

    /** The level's own shared updater, for when only one thread is in the level. */
    NeighborUpdater skein$vanillaNeighborUpdater();

    /**
     * Serialises the chunk-system paths that mutate {@code ChunkMap} and the
     * distance manager. Held only when a chunk actually has to be loaded, which
     * the lock-free hit path in {@code ServerChunkCacheMixin} makes rare;
     * reentrant because a load re-enters the chunk source while draining its
     * own task queue.
     */
    ReentrantLock skein$chunkLoadLock();

    /** Bumped by {@code ServerChunkCache.clearCache} to void every thread's {@link ChunkSlots}. */
    long skein$chunkGeneration();

    void skein$bumpChunkGeneration();

    /**
     * Changes whenever a block in this level changes. For caches of answers
     * that depend on block shapes and must notice any change in between.
     * Atomic, because a plain increment from two workers can land both on the
     * same value -- the one a reader snapshotted after the first write, which
     * then hides the second.
     */
    long skein$blockEpoch();

    void skein$bumpBlockEpoch();
}
