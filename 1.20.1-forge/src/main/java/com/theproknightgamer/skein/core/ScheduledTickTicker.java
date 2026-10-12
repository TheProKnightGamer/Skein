package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.ticks.ScheduledTick;

import java.util.List;
import java.util.Queue;
import java.util.function.BiConsumer;

/**
 * Scheduled block and fluid ticks, across connected components.
 *
 * <p>This is the phase where order is the whole point. Everything else Skein
 * parallelises can be reordered within a tick without anyone noticing; a
 * scheduled tick carries a sub-tick number precisely so that it cannot. So the
 * split here is {@link ClusterPartition} rather than the grid: work that could
 * possibly interact stays in one group, in vanilla's own order, and only groups
 * that are further apart than anything can reach run at the same time.
 *
 * <p>What that buys is an exact guarantee -- no pair of ticks that can affect
 * each other is ever reordered -- and what it costs is that parallelism depends
 * on the shape of the world rather than on how much work there is. A single
 * spreading lake is one component and runs exactly as vanilla runs it. Forty
 * separate farms are forty components.
 *
 * <p>Ticks whose reach is not bounded by the margin -- command blocks, which
 * run arbitrary commands -- cannot be placed in any component honestly. Rather
 * than pin them and reorder them against everything else, their presence makes
 * the whole container fall back to vanilla for that tick.
 *
 * <p>Groups that never meet still share the level's queues -- block events,
 * the entity list, the sub-tick counter -- and those are put back in vanilla's
 * order by {@link OrderedGroups}, which also lets a group that parks something
 * the rest of it could have seen be finished in order on this thread. Ticks
 * scheduled here are numbered as they are applied, so they get vanilla's
 * numbers too.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class ScheduledTickTicker {
    private ScheduledTickTicker() {}

    /** Whether the parallel path is permitted at all -- config, conflicts, the profiler. */
    public static boolean eligible(ServerLevel level) {
        return Skein.pool() != null
                && SkeinConfig.Live.enabled
                && SkeinConfig.Live.scheduledTicks
                && SkeinCompat.allows(TickMetrics.Phase.SCHEDULED_TICKS)
                && !DimensionTicker.isQuarantined(level)
                && level.getServer().getProfiler() instanceof InactiveProfiler;
    }

    /**
     * @return true when the ticks were run here, false to leave {@code toRun}
     *         untouched for vanilla's own loop
     */
    public static boolean run(Object container,
                              ServerLevel level,
                              Queue<ScheduledTick<?>> toRun,
                              List<ScheduledTick<?>> alreadyRun,
                              BiConsumer ticker,
                              ClusterPartition<ScheduledTick<?>> partition) {
        SkeinPool pool = Skein.pool();
        if (!eligible(level) || toRun.size() < SkeinConfig.Live.minScheduledTicks) {
            return false;
        }

        // Two ticks whose write zones could share a chunk must not run at once,
        // so the link distance is two margins plus a chunk rather than two
        // margins: cells in the grid split are chunk-aligned and get this for
        // free, components are not and do not.
        partition.begin(2 * SkeinConfig.Live.borderMargin + 16);
        for (ScheduledTick<?> tick : toRun) {
            if (SkeinConfig.Live.scheduledTickBlacklist.contains(tick.type())) {
                partition.release();
                return false;
            }
            partition.add(tick, tick.pos().getX(), tick.pos().getZ(), extraReach(tick.type()));
        }
        partition.finish();

        List<ClusterPartition.Group<ScheduledTick<?>>> groups = partition.groups();
        if (groups.size() < 2) {
            // One component is one task, which the pool runs on the caller
            // anyway. Leave it to vanilla rather than pay for a barrier.
            partition.release();
            return false;
        }
        alreadyRun.addAll(toRun);
        toRun.clear();

        int total = partition.size();
        int count = groups.size();
        ScheduledTickScope[] scopes = new ScheduledTickScope[count];
        for (int g = 0; g < count; g++) {
            scopes[g] = new ScheduledTickScope(container, groups.get(g).items());
        }

        // A group that could reach a chunk that is not loaded runs on this
        // thread, which may load it as vanilla would; a worker would have to
        // cut the tick short. Its ticks run at their own places in the merge.
        LoadedAround.beginTicks(level);
        boolean[] pinned = new boolean[count];
        for (int g = 0; g < count; g++) {
            pinned[g] = !LoadedAround.test(level, groups.get(g).items(), ScheduledTick::pos,
                    tick -> SkeinConfig.Live.borderMargin + extraReach(tick.type()));
        }

        OrderedGroups.Outcome outcome = OrderedGroups.run(level, pool, groups, pinned, total,
                TickMetrics.Phase.SCHEDULED_TICKS, new OrderedGroups.Work<ScheduledTick<?>>() {
                    @Override
                    public void enter(int group) {
                        TickContext.local().scheduledScope = scopes[group];
                    }

                    @Override
                    public void before(int group, ScheduledTick<?> tick) {
                        scopes[group].advance();
                    }

                    @Override
                    public void exit(int group) {
                        TickContext.local().scheduledScope = null;
                    }

                    @Override
                    public void tick(ScheduledTick<?> tick) {
                        ticker.accept(tick.pos(), tick.type());
                    }

                    @Override
                    public net.minecraft.core.BlockPos origin(ScheduledTick<?> tick) {
                        return tick.pos();
                    }

                    @Override
                    public int reach(ScheduledTick<?> tick) {
                        return SkeinConfig.Live.borderMargin;
                    }

                    @Override
                    public void failed(ServerLevel level, ScheduledTick<?> tick, Throwable thrown) {
                        Skein.LOGGER.error("Skein: scheduled tick for {} at {} threw in {}",
                                tick.type(), tick.pos(), level.dimension().location(), thrown);
                    }
                });

        TickMetrics.recordPhase(TickMetrics.Phase.SCHEDULED_TICKS, 1, count, total - outcome.serial(),
                outcome.serial(), outcome.applied());
        partition.release();
        if (outcome.fatal() != null) {
            throw new ReportedException(CrashReport.forThrowable(
                    outcome.fatal(), "Exception ticking scheduled tick (Skein scheduled ticks phase)"));
        }
        return true;
    }

    /**
     * How much further than the margin a tick of {@code type} reaches. A
     * tripwire hook's tick, or a string's, finds the hook at the other end of
     * the line up to 42 blocks away and sets it, with its neighbours updated.
     */
    private static int extraReach(Object type) {
        return type == Blocks.TRIPWIRE_HOOK || type == Blocks.TRIPWIRE ? 42 : 0;
    }
}
