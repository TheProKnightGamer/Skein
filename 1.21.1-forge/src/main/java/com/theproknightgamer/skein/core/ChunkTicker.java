package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;
import java.util.function.Consumer;

/**
 * Random ticks, across cells.
 *
 * <p>`ServerLevel.tickChunk` is where weather, ice and snow, and every
 * randomly-ticking block and fluid in a chunk get their turn. On a server with a
 * high {@code randomTickSpeed}, a lot of loaded chunks, or a lot of growing
 * things it is one of the largest single costs in the tick, and unlike the
 * entity loop it is embarrassingly parallel: a random tick reads and writes a
 * handful of blocks around itself and nothing else.
 *
 * <p>"A handful" is doing real work in that sentence, and it is what the margin
 * is for. Grass spreads one block, fire five, a sapling turns into a tree that
 * can be eight across. All of it is comfortably inside the default 32.
 *
 * <p>The unit here is a chunk rather than a point, so a chunk qualifies for a
 * pass only if its whole 16-block width is far enough inside the cell -- a block
 * edited at its border still has to land in the same cell. Which is the same
 * argument as everywhere else, applied to a span instead of a position.
 *
 * <p>Mob spawning, which vanilla interleaves with this in the same loop, stays
 * on the level-owning thread. See the README.
 */
public final class ChunkTicker {
    private ChunkTicker() {}

    /**
     * Whether this level's random ticks are being collected for a parallel run
     * rather than ticked inline. Decided once per level tick so the answer
     * cannot change halfway through the loop.
     */
    /** Whether the parallel path is permitted at all -- config, conflicts, profiler. */
    private static boolean eligible(ServerLevel level) {
        return Skein.pool() != null
                && SkeinConfig.Live.enabled
                && SkeinConfig.Live.randomTicks
                && SkeinCompat.allows(TickMetrics.Phase.RANDOM_TICKS)
                && !DimensionTicker.isQuarantined(level)
                && level.getServer().getProfiler() instanceof InactiveProfiler;
    }

    /** Decided once, in {@link #begin}; see {@link PhaseGovernor}. */
    public static boolean collecting(ServerLevel level) {
        return ((SkeinLevel) level).skein$governor().parallel(TickMetrics.Phase.RANDOM_TICKS);
    }

    /**
     * Resets the split. Called unconditionally at the top of vanilla's loop, so
     * that whether the phase runs is decided by what was collected rather than
     * by re-asking a question that could in principle answer differently
     * halfway through.
     */
    public static void begin(ServerLevel level) {
        ((SkeinLevel) level).skein$governor().begin(TickMetrics.Phase.RANDOM_TICKS, eligible(level));
        ((SkeinLevel) level).skein$chunkPartition().begin(SkeinConfig.Live.grid);
    }

    /** Files one chunk that vanilla was about to random-tick. */
    public static void collect(ServerLevel level, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        double minX = pos.getMinBlockX();
        double minZ = pos.getMinBlockZ();
        ((SkeinLevel) level).skein$chunkPartition().place(chunk, minX, minX + 16, minZ, minZ + 16);
    }

    /** Runs everything collected, in passes. Called where vanilla's loop ended. */
    public static void finish(ServerLevel level, int randomTickSpeed) {
        try {
            skein$finish(level, randomTickSpeed);
        } finally {
            ((SkeinLevel) level).skein$governor().finish(TickMetrics.Phase.RANDOM_TICKS);
        }
    }

    private static void skein$finish(ServerLevel level, int randomTickSpeed) {
        Partition<LevelChunk> partition = ((SkeinLevel) level).skein$chunkPartition();
        List<LevelChunk> all = partition.all();
        if (all.isEmpty()) {
            return;
        }
        Consumer<LevelChunk> action = chunk -> level.tickChunk(chunk, randomTickSpeed);
        if (all.size() < SkeinConfig.Live.minRandomTickChunks
                || !ParallelPass.run(level, partition, action, TickMetrics.Phase.RANDOM_TICKS, "chunk")) {
            for (int i = 0; i < all.size(); i++) {
                action.accept(all.get(i));
            }
        }
        partition.release();
    }
}
