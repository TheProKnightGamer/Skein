package com.theproknightgamer.skein.core;

import net.minecraft.util.profiling.Profiler;
import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.util.Util;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.ForgeEventFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Runs every {@code ServerLevel.tick} for one server tick concurrently, and
 * does not return until all of them have finished.
 *
 * <p>The barrier is the point. Dimensions overlap only within a single tick;
 * by the time the server thread moves on to connections, players and chunk
 * sending, every level is exactly one tick further along, same as vanilla. No
 * dimension can drift ahead of another, so nothing downstream has to reason
 * about a half-ticked world.
 *
 * <p>Event ordering is preserved on the server thread rather than inside the
 * workers: all {@code LevelTickEvent.Pre} fire before the phase opens and all
 * {@code Post} after it closes. Firing them from the workers would hand every
 * mod's tick listener a thread it has no reason to expect.
 */
public final class DimensionTicker {
    private DimensionTicker() {}

    /** Handed back to the vanilla loop when Skein has already ticked the levels. */
    public static final ServerLevel[] NO_LEVELS = new ServerLevel[0];

    private static volatile boolean phaseActive;
    private static final Set<ResourceKey<Level>> quarantined = ConcurrentHashMap.newKeySet();
    /** Levels whose tick reaches into other levels; they tick after the barrier. See {@link #reachedAcross}. */
    private static final Set<ResourceKey<Level>> reachesAcross = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<Runnable> pastBarrier = new ConcurrentLinkedQueue<>();

    /**
     * True while levels are ticking off the server thread. Read by
     * {@link com.theproknightgamer.skein.mixin.MinecraftServerMixin} to stop the server
     * thread servicing per-level chunk queues that a worker currently owns.
     */
    public static boolean phaseActive() {
        return phaseActive;
    }

    /**
     * Parks a write that lands on a level the calling thread does not own until
     * every level has stopped.
     *
     * <p>A per-cell {@link DeferredBuffer} is no help here, because it is
     * drained by its own level's coordinator while the level the write is aimed
     * at is still being ticked by somebody else. The dimension barrier is the
     * only point in the tick where that is not true of any level, so a tick that
     * reaches into another dimension -- a portal, in practice -- has to wait for
     * it.
     *
     * @return true when the action was parked, false when no phase is open and
     *         the caller should deal with it itself
     */
    public static boolean deferPastBarrier(Runnable action) {
        if (!phaseActive) {
            return false;
        }
        pastBarrier.add(action);
        return true;
    }

    /**
     * Whether the calling thread would be acting on a level it is not ticking
     * while dimensions tick in parallel: moving an entity out of {@code from}
     * or into {@code to} when either is somebody else's.
     */
    public static boolean crossesTickingLevel(Level from, Level to) {
        if (!phaseActive) {
            return false;
        }
        Level current = TickContext.current();
        return current != null && (from != current || to != current);
    }

    public static Set<ResourceKey<Level>> quarantined() {
        return quarantined;
    }

    /**
     * Whether {@code level} threw while ticking in parallel. A quarantined
     * level is serial in every sense, not only with respect to the other
     * dimensions: whatever raced there may just as well be inside it.
     */
    public static boolean isQuarantined(Level level) {
        return !quarantined.isEmpty() && quarantined.contains(level.dimension());
    }

    public static void clearQuarantine() {
        quarantined.clear();
        reachesAcross.clear();
    }

    /**
     * Records that {@code from}'s tick reached into {@code into} while both
     * were ticking -- which cannot be served safely -- and moves {@code from}
     * out of the parallel set: from the next tick it runs after the barrier,
     * where every other level has stopped. Unlike quarantine, its own in-level
     * phases keep running; nothing went wrong inside it.
     */
    public static void reachedAcross(Level from, Level into) {
        if (reachesAcross.add(from.dimension())) {
            Skein.LOGGER.warn("Skein: {} reached into {} while both were ticking in parallel (a command such as "
                            + "'execute in', or a mod that works on another dimension from this one's tick). {} now "
                            + "ticks after the other dimensions instead of beside them; the one request that did "
                            + "this was refused. /skein unquarantine puts it back.",
                    from.dimension().identifier(), into.dimension().identifier(), from.dimension().identifier());
        }
    }

    public static Set<ResourceKey<Level>> reachesAcross() {
        return reachesAcross;
    }

    /**
     * Since 26.1 vanilla broadcasts game time once per server tick, before the
     * level loop ({@code forceGameTimeSynchronization}), so unlike 1.21.x there
     * is no per-level time sync left inside the loop for this to replay.
     *
     * @param recordTime writes into the server's per-dimension tick-time ring,
     *                   which {@code /neoforge tps} reads
     * @return true when Skein ticked the levels itself; false to fall through
     *         to the vanilla loop
     */
    public static boolean tickLevels(MinecraftServer server,
                                     ServerLevel[] levels,
                                     BooleanSupplier haveTime,
                                     BiConsumer<ServerLevel, Long> recordTime) {
        SkeinPool pool = Skein.pool();
        if (pool == null || !SkeinConfig.Live.enabled || !SkeinConfig.Live.dimensions || levels.length < 2) {
            return false;
        }
        // ActiveProfiler is a plain ArrayList and HashMap behind an interface.
        // Rather than feed the workers a no-op profiler and hand back a profile
        // that quietly omits most of the server, stay serial for as long as
        // somebody is actually profiling.
        if (!(Profiler.get() instanceof InactiveProfiler)) {
            return false;
        }

        List<ServerLevel> parallel = new ArrayList<>(levels.length);
        List<ServerLevel> serial = new ArrayList<>(2);
        for (ServerLevel level : levels) {
            if (isExcluded(level)) {
                serial.add(level);
            } else {
                parallel.add(level);
            }
        }
        if (parallel.size() < 2) {
            // One parallel level means one worker doing the work while the
            // server thread waits: strictly worse than vanilla.
            return false;
        }

        long wallStart = Util.getNanos();

        for (ServerLevel level : levels) {
            ForgeEventFactory.onPreLevelTick(level, haveTime);
        }

        AtomicReference<Throwable> fatal = new AtomicReference<>();
        phaseActive = true;
        try {
            pool.invokeAll(parallel.size(), index -> tickOne(parallel.get(index), haveTime, recordTime, fatal));
        } finally {
            phaseActive = false;
        }

        // Everything one level's tick aimed at another, now that none of them
        // are running. Before the excluded levels tick, so a dimension pulled
        // out for safety still sees this tick's arrivals.
        for (Runnable action = pastBarrier.poll(); action != null; action = pastBarrier.poll()) {
            try {
                action.run();
            } catch (Throwable t) {
                Skein.LOGGER.error("Skein: deferred cross-level write failed", t);
            }
        }

        // Excluded levels tick on the server thread, after the barrier, so a
        // dimension pulled out for safety is never ticking next to one that is
        // not.
        for (ServerLevel level : serial) {
            tickOne(level, haveTime, recordTime, fatal);
        }

        for (ServerLevel level : levels) {
            ForgeEventFactory.onPostLevelTick(level, haveTime);
        }

        TickMetrics.recordWall(Util.getNanos() - wallStart);

        Throwable throwable = fatal.get();
        if (throwable != null) {
            // Deliberately re-thrown here rather than on the worker: the server
            // thread is where vanilla's crash reporter expects to be, and it is
            // the only thread whose death actually stops the server.
            CrashReport report = CrashReport.forThrowable(throwable, "Exception ticking world (Skein parallel phase)");
            throw new ReportedException(report);
        }
        return true;
    }

    private static boolean isExcluded(ServerLevel level) {
        ResourceKey<Level> key = level.dimension();
        return quarantined.contains(key)
                || reachesAcross.contains(key)
                || SkeinConfig.Live.dimensionBlacklist.contains(key.identifier().toString());
    }

    private static void tickOne(ServerLevel level,
                                BooleanSupplier haveTime,
                                BiConsumer<ServerLevel, Long> recordTime,
                                AtomicReference<Throwable> fatal) {
        long start = Util.getNanos();
        TickContext.State previous = TickContext.enter(level, false, null);
        try {
            level.tick(haveTime);
        } catch (Throwable t) {
            if (SkeinRefusal.in(t)) {
                // Refused a reach into another level: that level is already
                // out of the parallel set, and this tick ends here.
                TickContext.resetBrackets();
                SkeinRefusal.swallowed(level.dimension(), t);
                return;
            }
            TickMetrics.recordFailure();
            if (SkeinConfig.Live.crashPolicy == SkeinConfig.CrashPolicy.RETHROW) {
                fatal.compareAndSet(null, t);
            } else {
                quarantined.add(level.dimension());
                Skein.LOGGER.error(
                        "Skein: {} threw while ticking in parallel and has been dropped back to serial ticking "
                                + "for the rest of this session. Set general.crashPolicy=RETHROW for a crash report instead.",
                        level.dimension().identifier(), t);
            }
        } finally {
            TickContext.exit(previous);
            long elapsed = Util.getNanos() - start;
            recordTime.accept(level, elapsed);
            TickMetrics.recordLevel(level.dimension(), elapsed);
        }
    }
}
