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
     * Answers for one filing pass. Per thread, because each level files on the
     * thread that ticks it; and remembering the level as well, because one
     * thread can file for two levels in a tick.
     *
     * <p>Three layers, because neighbouring entities ask about nearly the same
     * square: by centre (the answer itself), by row (every chunk within the
     * margin along one row), and by chunk (one holder's state). A mob in the
     * next chunk over re-asks one new row and column instead of every holder
     * of its square -- with the default margin, 7 holders instead of 49.
     */
    private static final class Answers {
        ServerLevel level;
        int margin;
        /** By centre chunk: 1 = every chunk within the margin is FULL, 2 = not. */
        final Long2ByteOpenHashMap byChunk = new Long2ByteOpenHashMap();
        /** By chunk {@code (x, z)}: 1 = every chunk from {@code x - margin} to {@code x + margin} in row z is FULL. */
        final Long2ByteOpenHashMap rows = new Long2ByteOpenHashMap();
        /** By chunk: 1 = published at FULL, 2 = not. */
        final Long2ByteOpenHashMap full = new Long2ByteOpenHashMap();
    }

    private static final ThreadLocal<Answers> ANSWERS = ThreadLocal.withInitial(Answers::new);

    /** Starts a filing pass for {@code level}; earlier answers are for another pass. */
    static void begin(ServerLevel level) {
        Answers answers = ANSWERS.get();
        answers.level = level;
        answers.margin = SkeinConfig.Live.loadedMarginChunks;
        answers.byChunk.clear();
        answers.rows.clear();
        answers.full.clear();
    }

    static boolean test(ServerLevel level, Entity entity) {
        int margin = SkeinConfig.Live.loadedMarginChunks;
        if (margin <= 0) {
            return true;
        }
        Answers answers = ANSWERS.get();
        if (answers.level != level || answers.margin != margin) {
            begin(level);
        }
        ChunkPos centre = entity.chunkPosition();
        long key = centre.toLong();
        byte known = answers.byChunk.get(key);
        if (known != 0) {
            return known == 1;
        }
        ChunkMap map = level.getChunkSource().chunkMap;
        boolean ok = true;
        for (int dz = -margin; dz <= margin && ok; dz++) {
            ok = row(answers, map, centre.x, centre.z + dz, margin);
        }
        answers.byChunk.put(key, (byte) (ok ? 1 : 2));
        return ok;
    }

    private static boolean row(Answers answers, ChunkMap map, int x, int z, int margin) {
        long key = ChunkPos.asLong(x, z);
        byte known = answers.rows.get(key);
        if (known != 0) {
            return known == 1;
        }
        boolean ok = true;
        for (int dx = -margin; dx <= margin && ok; dx++) {
            ok = full(answers, map, x + dx, z);
        }
        answers.rows.put(key, (byte) (ok ? 1 : 2));
        return ok;
    }

    private static boolean full(Answers answers, ChunkMap map, int x, int z) {
        long key = ChunkPos.asLong(x, z);
        byte known = answers.full.get(key);
        if (known != 0) {
            return known == 1;
        }
        ChunkHolder holder = map.getVisibleChunkIfPresent(key);
        boolean ok = holder != null
                && holder.getTicketLevel() <= ChunkLevel.byStatus(ChunkStatus.FULL)
                && holder.getChunkIfPresent(ChunkStatus.FULL) != null;
        answers.full.put(key, (byte) (ok ? 1 : 2));
        return ok;
    }
}
