package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.SkeinConfig;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.Util;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the groups of a {@link ClusterPartition} on the pool and puts the
 * result back together in vanilla's order.
 *
 * <p>Each group runs on one thread in vanilla's order, and groups are further
 * apart than anything in them can reach, so no two things that could affect
 * each other are ever reordered. What groups do share is the level's queues --
 * block events, the entity list, the sub-tick counter, the block-entity
 * tickers, chunk tickets -- and a write to one of those is parked under the
 * rank of the item that made it, its place in vanilla's order. The coordinator
 * then walks the ranks in order and applies each item's writes, which is the
 * sequence vanilla would have made them in, so the queues end up exactly as
 * vanilla leaves them. A scheduled tick is numbered when its write is applied
 * ({@link SubTickOrder}), so it gets vanilla's number too.
 *
 * <p>Most parked writes are invisible to the rest of a group anyway: a block
 * event only joins a queue that runs later, and a parked tick still answers
 * {@code hasScheduledTick} on its own thread. A new entity is not -- a pressure
 * plate later in the group would have found it -- and nor is a removal or a
 * structure placement. So a group that parks one of those stops after that
 * item ({@link DeferredBuffer#takeObservable}), and the coordinator runs the
 * rest of it itself, each item at its own rank in the walk, with everything
 * vanilla would have applied before it already applied. A pinned group -- one
 * that has to run on the level's own thread, or might need a chunk loaded --
 * is run that way from the start.
 */
public final class OrderedGroups {
    private OrderedGroups() {}

    /** What a phase does with its items. */
    public interface Work<T> {
        /** Before the first item of a group, on whichever thread runs it. */
        default void enter(int group) {}

        /** Before each item. */
        default void before(int group, T item) {}

        /** After the last item a thread runs of a group. */
        default void exit(int group) {}

        void tick(T item);

        /** Where the item stands. */
        net.minecraft.core.BlockPos origin(T item);

        /** How far its tick can read or write, horizontally. */
        int reach(T item);

        /** An item that threw and was logged, under {@code crashPolicy = DISABLE}. */
        default void failed(ServerLevel level, T item, Throwable thrown) {}
    }

    /**
     * How a run went. {@code serial} items ran on the coordinator, and
     * {@code fatal} is what one threw under {@code crashPolicy = RETHROW}.
     */
    public record Outcome(int applied, int serial, Throwable fatal) {}

    /** Test switch: every group stops after its first item, so the walk runs nearly everything. */
    private static final boolean STOP_EVERY_ITEM = Boolean.getBoolean("skein.debug.scheduledStops");

    /** Items run by the coordinator after their group stopped, since the server started; read by tests. */
    static final java.util.concurrent.atomic.AtomicLong FINISHED_IN_ORDER = new java.util.concurrent.atomic.AtomicLong();

    /**
     * @param total  how many items were filed; ranks run from 0 to this
     * @param pinned per group, whether it has to run on this thread
     */
    public static <T> Outcome run(ServerLevel level,
                                  SkeinPool pool,
                                  List<ClusterPartition.Group<T>> groups,
                                  boolean[] pinned,
                                  int total,
                                  TickMetrics.Phase phase,
                                  Work<T> work) {
        int count = groups.size();
        int[] stops = new int[count];
        AtomicReference<Throwable> fatal = new AtomicReference<>();

        // Small groups are packed into a few tasks per thread rather than
        // dispatched one by one: a ring of hoppers is a group, and a task
        // apiece would cost more to hand out than the hoppers cost to tick.
        // Largest first, each onto the task with the least work so far.
        int taskCount = Math.min(count, (pool.size() + 1) * TASKS_PER_THREAD);
        int[] taskOf = new int[count];
        long[] load = new long[taskCount];
        int[] order = sizeOrder(groups);
        for (int k = 0; k < count; k++) {
            int g = order[k];
            int lightest = 0;
            for (int t = 1; t < taskCount; t++) {
                if (load[t] < load[lightest]) {
                    lightest = t;
                }
            }
            taskOf[g] = lightest;
            load[lightest] += pinned[g] ? 0 : groups.get(g).items().size();
        }

        long wallStart = Util.getNanos();
        pool.invokeAll(taskCount, task -> {
            for (int k = 0; k < count; k++) {
                int g = order[k];
                if (taskOf[g] != task || pinned[g]) {
                    continue;
                }
                if (fatal.get() != null) {
                    return;
                }
                stops[g] = runGroup(level, g, groups.get(g), phase, work, fatal);
            }
        });
        TickMetrics.recordPhaseWall(phase, Util.getNanos() - wallStart);

        int applied = 0;
        int serial = 0;
        try {
            if (fatal.get() == null) {
                long merged = merge(level, groups, stops, total, work);
                applied = (int) merged;
                serial = (int) (merged >>> 32);
            }
        } catch (Throwable t) {
            fatal.compareAndSet(null, t);
        }
        if (fatal.get() != null) {
            // On the way out of a crashing tick; order no longer matters.
            for (ClusterPartition.Group<T> group : groups) {
                applied += group.deferred().drain(level);
            }
        }
        if (serial > 0) {
            FINISHED_IN_ORDER.addAndGet(serial);
        }
        return new Outcome(applied, serial, fatal.get());
    }

    private static final int TASKS_PER_THREAD = 4;

    /**
     * Whether something {@code radius} blocks around {@code x, z} stays within
     * the reach of the item this thread is running in a task, horizontally --
     * where that item's own writes are, which no other task's come near.
     */
    @SuppressWarnings("unchecked")
    public static boolean within(TickContext.Local local, double x, double z, int radius) {
        Work<Object> work = (Work<Object>) local.ordered;
        Object item = local.ticking;
        if (work == null || item == null) {
            return false;
        }
        net.minecraft.core.BlockPos at = work.origin(item);
        double dx = Math.abs(Math.floor(x) - at.getX());
        double dz = Math.abs(Math.floor(z) - at.getZ());
        return Math.max(dx, dz) + radius <= work.reach(item);
    }

    /** Group indices, largest group first; vanilla's order breaks ties. */
    private static <T> int[] sizeOrder(List<ClusterPartition.Group<T>> groups) {
        Integer[] boxed = new Integer[groups.size()];
        for (int g = 0; g < boxed.length; g++) {
            boxed[g] = g;
        }
        java.util.Arrays.sort(boxed, (a, b) -> {
            int bySize = Integer.compare(groups.get(b).items().size(), groups.get(a).items().size());
            return bySize != 0 ? bySize : Integer.compare(groups.get(a).ranks().getInt(0), groups.get(b).ranks().getInt(0));
        });
        int[] order = new int[boxed.length];
        for (int g = 0; g < order.length; g++) {
            order[g] = boxed[g];
        }
        return order;
    }

    /**
     * Applies every group's parked writes in vanilla's order, and runs the
     * items of any group that stopped early at their place in it.
     *
     * <p>Rank {@code r} is the {@code r}-th item in vanilla's order. Walking the
     * ranks in order and, for each, applying what that item parked gives the
     * sequence of writes vanilla made. An item its group did not get to runs
     * right there instead, on this thread, with every write that came before it
     * in vanilla applied and none after: its effects land directly, as in
     * vanilla. Writes made by other groups' later items are still parked, and
     * what those items changed is further away than anything here can reach.
     *
     * @return the writes applied, with the items run here in the high half
     */
    private static <T> long merge(ServerLevel level,
                                  List<ClusterPartition.Group<T>> groups,
                                  int[] stops,
                                  int total,
                                  Work<T> work) {
        int count = groups.size();
        int[] owner = new int[total];
        // Ranks no group holds -- work the caller ran another way -- are skipped.
        java.util.Arrays.fill(owner, -1);
        for (int g = 0; g < count; g++) {
            IntArrayList ranks = groups.get(g).ranks();
            for (int i = 0; i < ranks.size(); i++) {
                owner[ranks.getInt(i)] = g;
            }
        }
        int[] next = new int[count];
        int applied = 0;
        int serial = 0;
        int entered = -1;
        try {
            for (int rank = 0; rank < total; rank++) {
                int g = owner[rank];
                if (g < 0) {
                    continue;
                }
                ClusterPartition.Group<T> group = groups.get(g);
                int position = next[g]++;
                if (position < stops[g]) {
                    applied += group.deferred().applyKey(level, rank);
                    continue;
                }
                T item = group.items().get(position);
                if (entered != g) {
                    if (entered >= 0) {
                        work.exit(entered);
                    }
                    work.enter(g);
                    entered = g;
                }
                work.before(g, item);
                serial++;
                try {
                    work.tick(item);
                } catch (Throwable t) {
                    TickMetrics.recordFailure();
                    if (SkeinConfig.Live.crashPolicy == SkeinConfig.CrashPolicy.RETHROW) {
                        throw t;
                    }
                    TickContext.resetBrackets();
                    work.failed(level, item, t);
                }
            }
        } finally {
            if (entered >= 0) {
                work.exit(entered);
            }
        }
        // Anything parked while the writes above were applied.
        for (ClusterPartition.Group<T> group : groups) {
            applied += group.deferred().drain(level);
        }
        return (long) serial << 32 | applied;
    }

    /**
     * Runs one group's items in order, parking each item's writes under its
     * rank, until one parks a write the rest of the group could have seen.
     *
     * @return how many of the group's items ran; the coordinator runs the rest
     */
    private static <T> int runGroup(ServerLevel level,
                                    int index,
                                    ClusterPartition.Group<T> group,
                                    TickMetrics.Phase phase,
                                    Work<T> work,
                                    AtomicReference<Throwable> fatal) {
        long start = Util.getNanos();
        List<T> items = group.items();
        IntArrayList ranks = group.ranks();
        DeferredBuffer buffer = group.deferred();
        TickContext.Local local = TickContext.local();
        TickContext.State previous = TickContext.enter(level, true, buffer);
        local.ordered = work;
        work.enter(index);
        try {
            for (int i = 0; i < items.size(); i++) {
                if ((buffer.takeObservable() || STOP_EVERY_ITEM) && i > 0) {
                    return i;
                }
                T item = items.get(i);
                buffer.key(ranks.getInt(i));
                work.before(index, item);
                local.ticking = item;
                try {
                    work.tick(item);
                } catch (Throwable t) {
                    if (SkeinRefusal.in(t)) {
                        TickContext.resetBrackets();
                        SkeinRefusal.swallowed(item, t);
                        continue;
                    }
                    TickMetrics.recordFailure();
                    if (SkeinConfig.Live.crashPolicy == SkeinConfig.CrashPolicy.RETHROW) {
                        fatal.compareAndSet(null, t);
                        return i + 1;
                    }
                    TickContext.resetBrackets();
                    work.failed(level, item, t);
                }
            }
            return items.size();
        } finally {
            local.ticking = null;
            local.ordered = null;
            work.exit(index);
            TickContext.exit(previous);
            TickMetrics.recordPhaseWork(phase, Util.getNanos() - start);
        }
    }
}
