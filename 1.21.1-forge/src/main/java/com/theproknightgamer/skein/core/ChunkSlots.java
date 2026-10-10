package com.theproknightgamer.skein.core;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Arrays;

/**
 * A private copy, per thread, of the chunk cache that {@code ServerChunkCache}
 * keeps in three parallel arrays.
 *
 * <p>That cache is the reason vanilla's {@code getChunk} is cheap. Without a
 * hit, every call falls through to {@code getChunkFutureMainThread}, which adds
 * a ticket to the distance manager -- a write, on a path that entities take
 * several times each per tick just to ask what fluid they are standing in.
 * Sharing vanilla's arrays across threads corrupts them; bypassing them turns
 * every fluid check into a chunk-system mutation. Neither is acceptable, so
 * each thread gets its own.
 *
 * <p>The shape matters more than the size, which took a measurement to learn.
 * A single tick of twenty thousand exploding TNT makes <em>a billion</em> calls
 * to this, and at that volume the cost is not misses -- there are none -- it is
 * how many cache lines a hit touches. A wide hashed table spread over five
 * arrays answers every call correctly and pays a memory stall for each one,
 * which is how a cache with a perfect hit rate ends up slower than vanilla's
 * four entries.
 *
 * <p>So the common case is answered before the table is consulted at all.
 * Chunk access is intensely local -- an explosion ray, a pathfinding step, a
 * fluid check all ask about the same chunk many times over -- and one remembered
 * entry catches the overwhelming majority on two compares against fields that
 * are already hot. The table behind it is there for the rest, and is sized to
 * stay in cache rather than to never miss.
 *
 * <p>Owner and generation are checked once per lookup rather than stored per
 * entry. Vanilla invalidates by calling {@code clearCache}, which can only clear
 * the arrays it can see; Skein instead bumps a counter on the level, and a
 * thread whose generation no longer matches drops its whole table. One thread's
 * invalidation is therefore every thread's, without touching another thread's
 * memory.
 */
public final class ChunkSlots {
    private static final int SIZE = 64;
    private static final int MASK = SIZE - 1;

    private Object owner;
    private long generation = Long.MIN_VALUE;

    /** The last chunk this thread asked for, which is nearly always the next one too. */
    private long lastPosition = ChunkPos.INVALID_CHUNK_POS;
    private ChunkStatus lastStatus;
    private ChunkAccess lastChunk;

    private final long[] positions = new long[SIZE];
    private final ChunkStatus[] statuses = new ChunkStatus[SIZE];
    private final ChunkAccess[] chunks = new ChunkAccess[SIZE];

    public ChunkAccess get(Object owner, long generation, long position, ChunkStatus status) {
        if (this.owner != owner || this.generation != generation) {
            reset(owner, generation);
            return null;
        }
        if (position == this.lastPosition && status == this.lastStatus) {
            return this.lastChunk;
        }
        int slot = index(position, status);
        if (this.positions[slot] == position && this.statuses[slot] == status) {
            ChunkAccess chunk = this.chunks[slot];
            this.lastPosition = position;
            this.lastStatus = status;
            this.lastChunk = chunk;
            return chunk;
        }
        return null;
    }

    public void put(Object owner, long generation, long position, ChunkStatus status, ChunkAccess chunk) {
        if (this.owner != owner || this.generation != generation) {
            reset(owner, generation);
        }
        int slot = index(position, status);
        this.positions[slot] = position;
        this.statuses[slot] = status;
        this.chunks[slot] = chunk;
        this.lastPosition = position;
        this.lastStatus = status;
        this.lastChunk = chunk;
    }

    private void reset(Object owner, long generation) {
        this.owner = owner;
        this.generation = generation;
        this.lastPosition = ChunkPos.INVALID_CHUNK_POS;
        this.lastStatus = null;
        this.lastChunk = null;
        Arrays.fill(this.positions, ChunkPos.INVALID_CHUNK_POS);
        Arrays.fill(this.statuses, null);
        Arrays.fill(this.chunks, null);
    }

    private static int index(long position, ChunkStatus status) {
        // Status is mixed in before the multiply, not after: two statuses of one
        // chunk have to land in different slots, and anything added afterwards
        // is shifted straight back out again.
        long mixed = (position ^ status.getIndex() * 0x2545F4914F6CDD1DL) * 0x9E3779B97F4A7C15L;
        return (int) (mixed >>> 40) & MASK;
    }
}
