package com.theproknightgamer.skein.core;

import net.minecraft.util.profiling.Profiler;
import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.util.Util;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.ticks.ScheduledTick;

import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
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
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class ScheduledTickTicker {
    private ScheduledTickTicker() {}

    /** Largest component first so the longest task starts earliest; sub-tick order breaks ties. */
    private static final Comparator<ClusterPartition.Group<ScheduledTick<?>>> LONGEST_FIRST =
            Comparator.<ClusterPartition.Group<ScheduledTick<?>>>comparingInt(g -> -g.items().size())
                    .thenComparingLong(g -> g.items().get(0).subTickOrder());

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
        if (pool == null
                || !SkeinConfig.Live.enabled
                || !SkeinConfig.Live.scheduledTicks
                || !SkeinCompat.allows(TickMetrics.Phase.SCHEDULED_TICKS)
                || DimensionTicker.isQuarantined(level)
                || toRun.size() < SkeinConfig.Live.minScheduledTicks
                || !(Profiler.get() instanceof InactiveProfiler)) {
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
            partition.add(tick, tick.pos().getX(), tick.pos().getZ());
        }
        partition.finish();

        List<ClusterPartition.Group<ScheduledTick<?>>> groups = partition.groups();
        if (groups.size() < 2) {
            // One component is one task, which the pool runs on the caller
            // anyway. Leave it to vanilla rather than pay for a barrier.
            partition.release();
            return false;
        }
        groups.sort(LONGEST_FIRST);

        alreadyRun.addAll(toRun);
        toRun.clear();

        AtomicReference<Throwable> fatal = new AtomicReference<>();
        int total = partition.size();

        long wallStart = Util.getNanos();
        pool.invokeAll(groups.size(), index -> runGroup(container, level, groups.get(index), ticker, fatal));
        TickMetrics.recordPhaseWall(TickMetrics.Phase.SCHEDULED_TICKS, Util.getNanos() - wallStart);

        int deferred = 0;
        for (ClusterPartition.Group<ScheduledTick<?>> group : groups) {
            deferred += group.deferred().drain(level);
        }

        TickMetrics.recordPhase(TickMetrics.Phase.SCHEDULED_TICKS, 1, groups.size(), total, 0, deferred);
        Throwable throwable = fatal.get();
        partition.release();
        if (throwable != null) {
            throw new ReportedException(CrashReport.forThrowable(
                    throwable, "Exception ticking scheduled tick (Skein scheduled ticks phase)"));
        }
        return true;
    }

    private static void runGroup(Object container,
                                 ServerLevel level,
                                 ClusterPartition.Group<ScheduledTick<?>> group,
                                 BiConsumer ticker,
                                 AtomicReference<Throwable> fatal) {
        long start = Util.getNanos();
        List<ScheduledTick<?>> items = group.items();
        ScheduledTickScope scope = new ScheduledTickScope(container, items);
        TickContext.Local local = TickContext.local();
        ScheduledTickScope previousScope = local.scheduledScope;
        TickContext.State previous = TickContext.enter(level, true, group.deferred());
        local.scheduledScope = scope;
        try {
            for (int i = 0; i < items.size(); i++) {
                ScheduledTick<?> tick = items.get(i);
                scope.advance();
                try {
                    ticker.accept(tick.pos(), tick.type());
                } catch (Throwable t) {
                    if (SkeinRefusal.in(t)) {
                        TickContext.resetBrackets();
                        SkeinRefusal.swallowed(tick.pos(), t);
                        continue;
                    }
                    TickMetrics.recordFailure();
                    if (SkeinConfig.Live.crashPolicy == SkeinConfig.CrashPolicy.RETHROW) {
                        fatal.compareAndSet(null, t);
                        return;
                    }
                    TickContext.resetBrackets();
                    Skein.LOGGER.error("Skein: scheduled tick for {} at {} threw in {}",
                            tick.type(), tick.pos(), level.dimension().identifier(), t);
                }
            }
        } finally {
            local.scheduledScope = previousScope;
            TickContext.exit(previous);
            TickMetrics.recordPhaseWork(TickMetrics.Phase.SCHEDULED_TICKS, Util.getNanos() - start);
        }
    }
}
