package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Block entities, across cells.
 *
 * <p>On most servers that have been played on for a while this is the biggest
 * cost in the tick: hoppers checking their neighbours, furnaces burning, chests
 * looking for players, spawners scanning. All of it is at a fixed position and
 * almost all of it reaches one or two blocks, which makes it a better fit for
 * the split than entities are.
 *
 * <p>Almost all. A handful of vanilla block entities reach much further than the
 * margin -- a beacon looks for players within fifty blocks and adds effects to
 * them, a conduit further still, a bell makes raiders glow at forty-eight, an
 * end gateway teleports things into another dimension entirely. Those tick on
 * the level-owning thread, and they are the default contents of
 * {@code blockEntities.blacklist}.
 *
 * <p>This is the phase most exposed to other mods. An entity that reaches across
 * the world is unusual; a machine that does is a genre. If a tech modpack
 * misbehaves, this is the first switch to try.
 */
public final class BlockEntityTicker {
    private BlockEntityTicker() {}

    /** Whether the parallel path is permitted at all -- config, conflicts, profiler. */
    private static boolean eligible(Level level) {
        return level instanceof ServerLevel serverLevel
                && Skein.pool() != null
                && SkeinConfig.Live.enabled
                && SkeinConfig.Live.blockEntities
                && SkeinCompat.allows(TickMetrics.Phase.BLOCK_ENTITIES)
                && !DimensionTicker.isQuarantined(level)
                && serverLevel.getServer().getProfiler() instanceof InactiveProfiler;
    }

    /**
     * Whether to file this tick's tickers rather than let vanilla run them
     * inline. Decided once, in {@link #begin}, so it cannot answer differently
     * halfway through the loop.
     */
    public static boolean collecting(Level level) {
        return ((SkeinLevel) level).skein$governor().parallel(TickMetrics.Phase.BLOCK_ENTITIES);
    }

    /** Resets the split. Called unconditionally at the top of vanilla's loop. */
    public static void begin(Level level) {
        ((SkeinLevel) level).skein$governor().begin(TickMetrics.Phase.BLOCK_ENTITIES, eligible(level));
        ((SkeinLevel) level).skein$blockEntityPartition().begin(SkeinConfig.Live.grid);
    }

    /** Files one ticker that vanilla was about to run. */
    public static void collect(Level level, TickingBlockEntity ticker) {
        Partition<TickingBlockEntity> partition = ((SkeinLevel) level).skein$blockEntityPartition();
        if (isPinned(ticker)) {
            partition.pin(ticker);
            return;
        }
        BlockPos pos = ticker.getPos();
        partition.place(ticker, pos.getX(), pos.getX(), pos.getZ(), pos.getZ());
    }

    /** Runs everything collected, in passes. Called where vanilla's loop ended. */
    public static void finish(Level level) {
        try {
            skein$finish(level);
        } finally {
            ((SkeinLevel) level).skein$governor().finish(TickMetrics.Phase.BLOCK_ENTITIES);
        }
    }

    private static void skein$finish(Level level) {
        Partition<TickingBlockEntity> partition = ((SkeinLevel) level).skein$blockEntityPartition();
        List<TickingBlockEntity> all = partition.all();
        if (all.isEmpty()) {
            return;
        }
        Consumer<TickingBlockEntity> action = TickingBlockEntity::tick;
        if (!(level instanceof ServerLevel serverLevel)
                || all.size() < SkeinConfig.Live.minBlockEntities
                || !ParallelPass.run(serverLevel, partition, action,
                        TickMetrics.Phase.BLOCK_ENTITIES, "block entity")) {
            for (int i = 0; i < all.size(); i++) {
                action.accept(all.get(i));
            }
        }
        partition.release();
    }

    private static boolean isPinned(TickingBlockEntity ticker) {
        Set<BlockEntityType<?>> blacklist = SkeinConfig.Live.blockEntityBlacklist;
        if (blacklist.isEmpty() && !Demotions.anyBlockEntities()) {
            return false;
        }
        BlockEntity blockEntity = ticker instanceof SkeinTicker skeinTicker
                ? skeinTicker.skein$blockEntity()
                : null;
        // A ticker Skein does not recognise is left in the parallel set: it is
        // some mod's own wrapper, and pinning everything it wraps would quietly
        // undo the phase on exactly the servers that need it.
        return blockEntity != null
                && (blacklist.contains(blockEntity.getType()) || Demotions.blockEntity(blockEntity.getType()));
    }
}
