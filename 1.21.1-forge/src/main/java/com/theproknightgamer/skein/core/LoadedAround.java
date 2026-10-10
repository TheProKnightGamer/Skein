package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.SkeinConfig;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Whether every chunk within {@code entities.loadedMarginChunks} of an entity
 * is published at FULL -- the condition under which every block read its tick
 * can make is answered from the chunk map. Anything closer than that to the
 * edge of the loaded area is pinned, because a read past the edge loads the
 * chunk, and a parallel tick is not allowed to (see
 * {@code ServerChunkCacheMixin#skein$guardChunkFetch}): on the level's own
 * thread the load is the same synchronous one vanilla does.
 */
final class LoadedAround {
    private LoadedAround() {}

    /**
     * Per-chunk answers for one filing pass. Per thread, because each level
     * files on the thread that ticks it; and remembering the level as well,
     * because one thread can file for two levels in a tick.
     */
    private static final class Answers {
        ServerLevel level;
        final Long2ByteOpenHashMap byChunk = new Long2ByteOpenHashMap();
    }

    private static final ThreadLocal<Answers> ANSWERS = ThreadLocal.withInitial(Answers::new);

    /** Starts a filing pass for {@code level}; earlier answers are for another pass. */
    static void begin(ServerLevel level) {
        Answers answers = ANSWERS.get();
        answers.level = level;
        answers.byChunk.clear();
    }

    static boolean test(ServerLevel level, Entity entity) {
        int margin = SkeinConfig.Live.loadedMarginChunks;
        if (margin <= 0) {
            return true;
        }
        Answers answers = ANSWERS.get();
        if (answers.level != level) {
            begin(level);
        }
        ChunkPos centre = entity.chunkPosition();
        long key = centre.toLong();
        byte known = answers.byChunk.get(key);
        if (known != 0) {
            return known == 1;
        }
        ChunkMap map = level.getChunkSource().chunkMap;
        int full = ChunkLevel.byStatus(ChunkStatus.FULL);
        boolean ok = true;
        scan:
        for (int dx = -margin; dx <= margin; dx++) {
            for (int dz = -margin; dz <= margin; dz++) {
                ChunkHolder holder = map.getVisibleChunkIfPresent(ChunkPos.asLong(centre.x + dx, centre.z + dz));
                if (holder == null
                        || holder.getTicketLevel() > full
                        || holder.getChunkIfPresent(ChunkStatus.FULL) == null) {
                    ok = false;
                    break scan;
                }
            }
        }
        answers.byChunk.put(key, (byte) (ok ? 1 : 2));
        return ok;
    }
}
