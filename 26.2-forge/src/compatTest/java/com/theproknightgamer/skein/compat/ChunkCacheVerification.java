package com.theproknightgamer.skein.compat;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.core.SkeinLevel;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** The serial level owner uses the same invalidation contract as cell workers. */
final class ChunkCacheVerification {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    static void run(ServerLevel level) throws Exception {
        var context = TickContext.enter(level, false, null);
        try {
            var source = level.getChunkSource();
            var owner = (SkeinLevel) level;
            var slots = TickContext.local().chunkSlots;
            long position = ChunkPos.pack(0, 0);
            var chunk = level.getChunk(0, 0);
            long generation = owner.skein$chunkGeneration();
            check(slots.get(source, generation, position, ChunkStatus.FULL) == chunk, "owner fetch did not populate private cache");
            var clear = ServerChunkCache.class.getDeclaredMethod("clearCache");
            clear.setAccessible(true);
            clear.invoke(source);
            check(owner.skein$chunkGeneration() != generation, "vanilla cache clear did not invalidate private caches");
            check(slots.get(source, owner.skein$chunkGeneration(), position, ChunkStatus.FULL) == null, "stale owner cache survived clearCache");
            check(level.getChunk(0, 0) == chunk, "cache miss returned wrong loaded chunk");
            check(slots.get(new Object(), owner.skein$chunkGeneration(), position, ChunkStatus.FULL) == null, "cache crossed chunk-source ownership");
            check(level.getChunk(0, 0) == chunk, "ownership reset lost loaded chunk");
            Skein.LOGGER.info("Chunk cache verification passed: serial-owner hits, vanilla invalidation, and source isolation");
        } finally { TickContext.exit(context); }
    }
}
