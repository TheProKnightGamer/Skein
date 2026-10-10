package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.Skein;
import net.minecraft.server.level.ServerLevel;

import java.util.function.IntConsumer;

/**
 * Read-only per-item work spread over the pool, in slices.
 *
 * <p>On 1.21.1 this class also threads the chunk tick's two walks over every
 * loaded chunk holder -- one for the ticking chunk, one for the broadcast.
 * 1.21.11's chunk tick makes neither: it visits only spawn candidates and
 * entity-ticking chunks, and broadcasts only the holders queued as changed.
 * What is left is the slicing helper the tracking pass shares, kept under this
 * name so {@link TrackingPass} is the same code on both versions.
 */
public final class ChunkScan {
    private ChunkScan() {}

    private static final java.util.concurrent.atomic.LongAdder SCANS = new java.util.concurrent.atomic.LongAdder();

    /** Slice runs on the pool, for tests and benchmarks. */
    public static long scans() {
        return SCANS.sum();
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

}
