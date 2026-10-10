package com.theproknightgamer.skein.core;

import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A level owns a complete collision cache per calling thread. Keeping these on
 * the level, rather than in worker ThreadLocals, lets an unloaded level and the
 * entities in its collider lists be collected together.
 */
public final class SpeeDeeCache {
    /** Implemented by the optional mixin into SpeeDeeEntity's TickCache. */
    public interface Entry {
        void skein$clear(long tick);
    }

    private static final class Slot {
        final Entry cache;
        long tick = Long.MIN_VALUE;
        long revision = Long.MIN_VALUE;

        Slot(Entry cache) {
            this.cache = cache;
        }
    }

    private final ConcurrentHashMap<Thread, Slot> slots = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong();
    private final Supplier<Entry> factory;

    public SpeeDeeCache(Supplier<Entry> factory) {
        this.factory = factory;
    }

    public Entry get(long tick) {
        Thread thread = Thread.currentThread();
        Slot slot = slots.get(thread);
        if (slot == null) {
            slot = new Slot(factory.get());
            slots.put(thread, slot);
        }
        long current = revision.get();
        if (slot.tick != tick || slot.revision != current) {
            slot.cache.skein$clear(tick);
            slot.tick = tick;
            slot.revision = current;
        }
        return slot.cache;
    }

    /** Readers empty their own maps, never a map another thread is using. */
    public void invalidate() {
        revision.incrementAndGet();
    }

    /** One tick's colliders, frozen: every thread in the level reads the same list. */
    private record Colliders(long tick, long epoch, List<Entity> list) {}

    private volatile Colliders colliders;
    private Entry colliderBuilder;

    /**
     * The entities anything can collide with -- boats, minecarts, shulkers --
     * for this tick, built once for the level instead of once per thread.
     *
     * <p>SpeeDeeEntity builds this list by walking every entity in the level
     * and keeps it for the rest of the tick in the thread's cache. With a cache
     * per thread, every worker that moves an entity walks the whole level again
     * to arrive at the same few colliders: measured with 3,600 entities loaded,
     * that walk was a fifth of all worker time. The list depends only on the
     * tick and on SpeeDeeEntity's epoch, which it bumps when a collider enters
     * or leaves an entity section -- and Skein parks section moves to the
     * barrier, so the epoch cannot move while workers are reading. The first
     * caller of a tick builds it under the lock into an immutable copy; every
     * other caller takes the published copy without locking.
     */
    public List<Entity> colliders(long tick, long epoch, Function<Object, List<Entity>> build) {
        Colliders snapshot = this.colliders;
        if (snapshot != null && snapshot.tick == tick && snapshot.epoch == epoch) {
            return snapshot.list;
        }
        synchronized (this) {
            snapshot = this.colliders;
            if (snapshot != null && snapshot.tick == tick && snapshot.epoch == epoch) {
                return snapshot.list;
            }
            if (this.colliderBuilder == null) {
                this.colliderBuilder = this.factory.get();
            }
            // Null means SpeeDeeEntity has no entity view for this level and
            // wants its caller to fall back to vanilla; keep that answer too.
            List<Entity> built = build.apply(this.colliderBuilder);
            List<Entity> list = built == null ? null : List.copyOf(built);
            this.colliders = new Colliders(tick, epoch, list);
            return list;
        }
    }
}
