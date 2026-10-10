package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The two walks over every loaded chunk that the chunk tick makes, spread over
 * the pool.
 *
 * <p>{@code ServerChunkCache.tickChunks} looks at every chunk holder in the
 * level twice. First it asks each one for its ticking chunk to build the list
 * it loops over; last it asks each one to broadcast its changes, which almost
 * every one of them answers by reading three fields and returning. Neither look
 * does any work to speak of. Each one is a few reads that start from a holder
 * nothing has touched since the last tick, so nearly every read misses the CPU
 * cache and waits on memory. With a view distance of 32 that is some 4,500
 * holders: about a millisecond for the first walk and half that for the second,
 * as much as the rest of the chunk tick put together.
 *
 * <p>Memory waits overlap across cores, so both looks run on the pool, in
 * slices, and only read. The first records each holder's ticking chunk for the
 * loop to pick up in order; the second picks out the holders with something to
 * send. Everything that writes -- the loop, the spawner, the broadcasts
 * themselves -- still runs on the level-owning thread, in vanilla's order.
 */
public final class ChunkScan {
    private ChunkScan() {}

    /** Below this many holders the walk is cheaper than handing it out. Tests lower it. */
    public static volatile int minHolders = 1024;

    private static final java.util.concurrent.atomic.LongAdder SCANS = new java.util.concurrent.atomic.LongAdder();

    /** Walks run on the pool, for tests and benchmarks. */
    public static long scans() {
        return SCANS.sum();
    }

    /** Implemented by {@code ServerChunkCache$ChunkAndHolder}, the loop's list entries. */
    public interface Entry {
        ChunkHolder skein$holder();

        LevelChunk skein$chunk();
    }

    /** Implemented by {@code ChunkHolder}: vanilla's own test for having anything to broadcast. */
    public interface Changes {
        boolean skein$hasChangedSections();

        java.util.BitSet skein$skyChangedLightSectionFilter();

        java.util.BitSet skein$blockChangedLightSectionFilter();
    }

    public static boolean eligible() {
        return SkeinConfig.Live.enabled && SkeinConfig.Live.parallelChunkScan
                && Skein.pool() != null && Skein.pool().size() > 1;
    }

    /**
     * Runs {@code body} for every index below {@code size}, in slices on the
     * pool. Each helper runs as a worker of {@code level}, so whatever another
     * mod's code does from inside a look (a chunk ticket) is parked, and is
     * applied here afterwards in slice order.
     */
    public static void run(ServerLevel level, int size, IntConsumer body) {
        SCANS.increment();
        // A few slices per worker, so a slow one can be picked up by another.
        int slice = Math.max(16, size / (Skein.pool().size() * 4) + 1);
        int slices = (size + slice - 1) / slice;
        DeferredBuffer[] parked = new DeferredBuffer[slices];
        Skein.pool().invokeAll(slices, index -> {
            DeferredBuffer buffer = new DeferredBuffer();
            parked[index] = buffer;
            TickContext.State previous = TickContext.enter(level, true, buffer);
            try {
                for (int i = index * slice, end = Math.min(size, i + slice); i < end; i++) {
                    body.accept(i);
                }
            } finally {
                TickContext.exit(previous);
            }
        });
        DeferredBuffer own = TickContext.deferring() ? TickContext.buffer() : null;
        for (DeferredBuffer buffer : parked) {
            if (buffer == null || buffer.isEmpty()) {
                continue;
            }
            if (own != null) {
                buffer.transferTo(own);
            } else {
                buffer.drain(level);
            }
        }
    }

    /**
     * The loop's entries whose holders have something to broadcast, in the
     * loop's order. A holder is kept unless vanilla's {@code broadcastChanges}
     * would return at its first test -- no changed sections and no changed light
     * -- or its class overrides that method, which then decides for itself.
     */
    public static <E> List<E> changed(ServerLevel level, List<E> entries) {
        int size = entries.size();
        boolean[] keep = new boolean[size];
        run(level, size, i -> {
            // An entry some other mod put there is its own business: keep it.
            if (!(entries.get(i) instanceof Entry entry)) {
                keep[i] = true;
                return;
            }
            ChunkHolder holder = entry.skein$holder();
            keep[i] = OVERRIDES_BROADCAST.get(holder.getClass()) || hasChanges((Changes) holder);
        });
        List<E> changed = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (keep[i]) {
                changed.add(entries.get(i));
            }
        }
        return changed;
    }

    private static boolean hasChanges(Changes holder) {
        return holder.skein$hasChangedSections() || !holder.skein$skyChangedLightSectionFilter().isEmpty()
                || !holder.skein$blockChangedLightSectionFilter().isEmpty();
    }

    private static final ClassValue<Boolean> OVERRIDES_BROADCAST = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("broadcastChanges", LevelChunk.class).getDeclaringClass() != ChunkHolder.class;
            } catch (NoSuchMethodException | LinkageError unanswerable) {
                return true;
            }
        }
    };
}
