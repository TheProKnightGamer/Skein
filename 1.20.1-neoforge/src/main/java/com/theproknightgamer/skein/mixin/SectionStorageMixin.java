package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.SkeinPoiIndex;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * The section map and dirty set behind the point-of-interest index, put behind
 * one lock.
 *
 * <p>{@code SectionStorage} keeps its sections in a plain
 * {@code Long2ObjectOpenHashMap} and its unsaved keys in a plain
 * {@code LongLinkedOpenHashSet}, and its only subclass is {@code PoiManager} --
 * which villagers, iron golems, raiders and bees all reach straight out of
 * their ticks, and which a lightning strike reaches from a random tick. It is
 * one object per level with no synchronisation of any kind, so it is the same
 * shape of problem as {@code RedstoneTorchBlock.RECENT_TOGGLES} and has the
 * same failure: a query that misses reads its column off disk and puts a
 * section per height slice into the map, and a resize while another worker is
 * probing it leaves that worker in the open-address loop for good.
 *
 * <p>Distance is no help here. Two villagers three hundred blocks apart are in
 * different cells by construction, but they are in the same hash table, which
 * is exactly why {@code PathTypeCache} needed fixing too.
 *
 * <p>The lock is very nearly a leaf: nothing under it takes another lock or
 * calls into mod code. The one thing it does hold across is the blocking read
 * in {@code readColumn}, which is a column of POI data off disk -- rare once a
 * region is resident, and the alternative is publishing a half-built map.
 */
@SuppressWarnings("rawtypes")
@HookFor(Hooks.Feature.IN_LEVEL)
@Mixin(SectionStorage.class)
public abstract class SectionStorageMixin implements SkeinPoiIndex {

    @Unique
    private final ReentrantLock skein$poiLock = new ReentrantLock();

    @Override
    public ReentrantLock skein$poiLock() {
        return this.skein$poiLock;
    }

    @WrapMethod(method = "get")
    private Optional skein$lockGet(long sectionKey, Operation<Optional> original) {
        if (this.skein$poiLock.isHeldByCurrentThread()) {
            return original.call(sectionKey);
        }
        this.skein$poiLock.lock();
        try {
            return original.call(sectionKey);
        } finally {
            this.skein$poiLock.unlock();
        }
    }

    @WrapMethod(method = "getOrLoad")
    private Optional skein$lockGetOrLoad(long sectionKey, Operation<Optional> original) {
        if (this.skein$poiLock.isHeldByCurrentThread()) {
            return original.call(sectionKey);
        }
        this.skein$poiLock.lock();
        try {
            return original.call(sectionKey);
        } finally {
            this.skein$poiLock.unlock();
        }
    }

    @WrapMethod(method = "getOrCreate")
    private Object skein$lockGetOrCreate(long sectionKey, Operation<Object> original) {
        if (this.skein$poiLock.isHeldByCurrentThread()) {
            return original.call(sectionKey);
        }
        this.skein$poiLock.lock();
        try {
            return original.call(sectionKey);
        } finally {
            this.skein$poiLock.unlock();
        }
    }

    @WrapMethod(method = "setDirty")
    private void skein$lockSetDirty(long sectionPos, Operation<Void> original) {
        if (this.skein$poiLock.isHeldByCurrentThread()) {
            original.call(sectionPos);
            return;
        }
        this.skein$poiLock.lock();
        try {
            original.call(sectionPos);
        } finally {
            this.skein$poiLock.unlock();
        }
    }

    /**
     * The save side runs on the thread that owns the level rather than in a
     * cell, so it is not what the lock is for -- but it walks the same two
     * structures, and a worker that reaches the index from an entity tick has
     * no way to know that.
     */
    @WrapMethod(method = "tick")
    private void skein$lockTick(BooleanSupplier aheadOfTime, Operation<Void> original) {
        if (this.skein$poiLock.isHeldByCurrentThread()) {
            original.call(aheadOfTime);
            return;
        }
        this.skein$poiLock.lock();
        try {
            original.call(aheadOfTime);
        } finally {
            this.skein$poiLock.unlock();
        }
    }

    @WrapMethod(method = "hasWork")
    private boolean skein$lockHasWork(Operation<Boolean> original) {
        if (this.skein$poiLock.isHeldByCurrentThread()) {
            return original.call();
        }
        this.skein$poiLock.lock();
        try {
            return original.call();
        } finally {
            this.skein$poiLock.unlock();
        }
    }

    // Chunk saving flushes sections too, from the pool when saving is parallel.
    @HookFor({Hooks.Feature.IN_LEVEL, Hooks.Feature.PARALLEL_SAVING})
    @WrapMethod(method = "flush")
    private void skein$lockFlush(ChunkPos chunkPos, Operation<Void> original) {
        if (this.skein$poiLock.isHeldByCurrentThread()) {
            original.call(chunkPos);
            return;
        }
        this.skein$poiLock.lock();
        try {
            original.call(chunkPos);
        } finally {
            this.skein$poiLock.unlock();
        }
    }
}
