package com.theproknightgamer.skein.core;

import net.minecraft.util.profiling.Profiler;
import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

import java.util.ArrayList;
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
 *
 * <p>With {@code blockEntities.vanillaOrderLimit} above 0, block entities that
 * could affect each other tick in vanilla's order. A grid cell edge can fall
 * between a moving piston and the observer it is about to trigger, or between
 * two hoppers of one clock, and the grid runs them in two passes in whichever
 * order the passes come. Ordered, they are grouped by what each can actually
 * reach ({@link BlockEntityReach}) and the groups run side by side in vanilla's
 * order ({@link OrderedGroups}); a group bigger than the limit is split by the
 * grid. It is off by default because a dense contraption is one group: a TNT
 * cannon's moving pistons all reach each other and would tick on one thread.
 */
public final class BlockEntityTicker {
    private BlockEntityTicker() {}

    /** Whether the parallel path is permitted at all -- config, conflicts, profiler. */
    private static boolean eligible(Level level) {
        return level instanceof ServerLevel serverLevel
                && Skein.pool() != null
                && SkeinConfig.Live.enabled
                && SkeinConfig.Live.blockEntities
                && !DimensionTicker.isQuarantined(level)
                && SkeinCompat.allows(TickMetrics.Phase.BLOCK_ENTITIES)
                && Profiler.get() instanceof InactiveProfiler;
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
        if (SkeinConfig.Live.blockEntityOrderLimit > 0) {
            // Grouped in finish; only what is too dense for that is placed in cells.
            partition.file(ticker);
            return;
        }
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
                || !(ordered(serverLevel, partition, action)
                        || ParallelPass.run(serverLevel, partition, action,
                                TickMetrics.Phase.BLOCK_ENTITIES, "block entity"))) {
            for (int i = 0; i < all.size(); i++) {
                action.accept(all.get(i));
            }
        }
        partition.release();
    }

    /**
     * Runs the tickers in groups of what can reach each other, each group in
     * vanilla's order; any group too big for that goes through the grid
     * afterwards, refiled into {@code partition}.
     *
     * @return true when everything ran, false to leave {@code partition} as
     *         filed for the grid -- everything is one dense build, or the
     *         ordering is switched off
     */
    private static boolean ordered(ServerLevel level, Partition<TickingBlockEntity> partition,
                                   Consumer<TickingBlockEntity> action) {
        int limit = SkeinConfig.Live.blockEntityOrderLimit;
        SkeinPool pool = Skein.pool();
        if (limit <= 0 || pool == null) {
            return false;
        }
        List<TickingBlockEntity> all = partition.all();
        int total = all.size();
        ClusterPartition<TickingBlockEntity> split = ((SkeinLevel) level).skein$blockEntityGroups();
        // Joined at their two reaches plus a chunk, for the same reason as
        // scheduled ticks: write zones closer than that could share a chunk.
        split.begin(2 * SkeinConfig.Live.borderMargin + 16);
        for (int i = 0; i < total; i++) {
            TickingBlockEntity ticker = all.get(i);
            BlockPos pos = ticker.getPos();
            split.addLinking(ticker, pos.getX(), pos.getZ(), 2 * BlockEntityReach.of(ticker) + 16);
        }
        split.finish();

        List<ClusterPartition.Group<TickingBlockEntity>> groups = new ArrayList<>();
        boolean[] dense = null;
        for (ClusterPartition.Group<TickingBlockEntity> group : split.groups()) {
            if (group.items().size() <= limit) {
                groups.add(group);
                continue;
            }
            if (dense == null) {
                dense = new boolean[total];
            }
            for (int i = 0; i < group.ranks().size(); i++) {
                dense[group.ranks().getInt(i)] = true;
            }
        }
        if (groups.isEmpty()) {
            // Everything is one dense build: the grid, as before.
            split.release();
            place(partition, new ArrayList<>(all));
            return false;
        }
        if (groups.size() == 1 && dense == null) {
            // One group is vanilla's loop with a barrier added.
            for (int i = 0; i < total; i++) {
                action.accept(all.get(i));
            }
            split.release();
            return true;
        }
        // A group with a ticker that has to stay on this thread, or one that
        // could reach a chunk that is not loaded, runs here at its places in the
        // merge. A worker may not load a chunk, and would cut the tick short.
        LoadedAround.beginTicks(level);
        boolean[] pinned = new boolean[groups.size()];
        for (int g = 0; g < groups.size(); g++) {
            List<TickingBlockEntity> items = groups.get(g).items();
            boolean pin = false;
            for (int i = 0; i < items.size() && !pin; i++) {
                pin = isPinned(items.get(i));
            }
            pinned[g] = pin || !LoadedAround.test(level, items, TickingBlockEntity::getPos, BlockEntityReach::of);
        }

        // The dense rest, in vanilla's order, collected before the grid
        // partition is refiled with only them.
        List<TickingBlockEntity> rest = null;
        if (dense != null) {
            rest = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                if (dense[i]) {
                    rest.add(all.get(i));
                }
            }
        }

        OrderedGroups.Outcome outcome = OrderedGroups.run(level, pool, groups, pinned, total,
                TickMetrics.Phase.BLOCK_ENTITIES, new OrderedGroups.Work<TickingBlockEntity>() {
                    @Override
                    public void tick(TickingBlockEntity ticker) {
                        ticker.tick();
                    }

                    @Override
                    public BlockPos origin(TickingBlockEntity ticker) {
                        return ticker.getPos();
                    }

                    @Override
                    public int reach(TickingBlockEntity ticker) {
                        return BlockEntityReach.of(ticker);
                    }

                    @Override
                    public void failed(ServerLevel level, TickingBlockEntity ticker, Throwable thrown) {
                        Skein.LOGGER.error("Skein: {} threw during parallel block entity tick in {}",
                                ticker, level.dimension().identifier(), thrown);
                        Demotions.threw(ticker, level, thrown);
                    }
                });
        int ordered = total - (rest == null ? 0 : rest.size());
        TickMetrics.recordPhase(TickMetrics.Phase.BLOCK_ENTITIES, 1, groups.size(),
                ordered - outcome.serial(), outcome.serial(), outcome.applied());
        split.release();
        if (outcome.fatal() != null) {
            partition.release();
            throw new ReportedException(CrashReport.forThrowable(
                    outcome.fatal(), "Exception ticking block entity (Skein block entities phase)"));
        }

        if (rest != null) {
            place(partition, rest);
            if (!ParallelPass.run(level, partition, action, TickMetrics.Phase.BLOCK_ENTITIES, "block entity")) {
                for (TickingBlockEntity ticker : rest) {
                    action.accept(ticker);
                }
            }
        }
        return true;
    }

    /** Refiles {@code tickers} into the grid, alone and in their order. */
    private static void place(Partition<TickingBlockEntity> partition, List<TickingBlockEntity> tickers) {
        partition.begin(SkeinConfig.Live.grid);
        for (TickingBlockEntity ticker : tickers) {
            if (isPinned(ticker)) {
                partition.pin(ticker);
            } else {
                BlockPos pos = ticker.getPos();
                partition.place(ticker, pos.getX(), pos.getX(), pos.getZ(), pos.getZ());
            }
        }
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
