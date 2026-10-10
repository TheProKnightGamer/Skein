package com.theproknightgamer.skein.core;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enough numbers to answer "is this actually helping?", per phase.
 *
 * <p>The one that matters for a phase is its overlap: CPU time spent inside its
 * cell tasks divided by the wall-clock its barriers were open. At 1.0 the phase
 * is pure overhead. The dimension figure is the same idea one level up -- level
 * tick time over the wall-clock the dimension barrier was open -- and it only
 * exceeds 1.0 when dimensions genuinely have work at the same time, which on a
 * single-dimension server they never do. The per-phase numbers do not have that
 * limitation, which is why they are reported separately.
 */
public final class TickMetrics {
    private TickMetrics() {}

    /** The parallel phases inside one level's tick, in the order they run. */
    public enum Phase {
        SCHEDULED_TICKS("scheduled ticks"),
        RANDOM_TICKS("random ticks"),
        ENTITIES("entities"),
        BLOCK_ENTITIES("block entities");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

    /** Per-tick counters for one phase, summed across every level. */
    public static final class Stats {
        private final AtomicLong work = new AtomicLong();
        private final AtomicLong wall = new AtomicLong();
        private final AtomicLong passes = new AtomicLong();
        private final AtomicLong cells = new AtomicLong();
        private final AtomicLong parallel = new AtomicLong();
        private final AtomicLong serial = new AtomicLong();
        private final AtomicLong deferred = new AtomicLong();

        private void reset() {
            this.work.set(0);
            this.wall.set(0);
            this.passes.set(0);
            this.cells.set(0);
            this.parallel.set(0);
            this.serial.set(0);
            this.deferred.set(0);
        }

        public double overlap() {
            long open = this.wall.get();
            return open <= 0 ? 0.0 : (double) this.work.get() / open;
        }

        public double workMillis() {
            return this.work.get() / 1_000_000.0;
        }

        public double wallMillis() {
            return this.wall.get() / 1_000_000.0;
        }

        public long passes() {
            return this.passes.get();
        }

        public long cells() {
            return this.cells.get();
        }

        public long parallel() {
            return this.parallel.get();
        }

        public long serial() {
            return this.serial.get();
        }

        public long deferred() {
            return this.deferred.get();
        }

        public boolean ran() {
            return this.wall.get() > 0;
        }
    }

    private static final Stats[] PHASES = new Stats[Phase.values().length];

    static {
        for (int i = 0; i < PHASES.length; i++) {
            PHASES[i] = new Stats();
        }
    }

    public static Stats of(Phase phase) {
        return PHASES[phase.ordinal()];
    }

    /** Nanoseconds of level-tick work summed across threads, last tick. */
    private static final AtomicLong lastWorkNanos = new AtomicLong();
    /** Nanoseconds the server thread spent inside the dimension phase, last tick. */
    private static volatile long lastWallNanos;

    /** What the chunk source cost this tick, summed once per cell rather than per call. */
    private static final AtomicLong chunkHits = new AtomicLong();
    private static final AtomicLong chunkMisses = new AtomicLong();
    private static final AtomicLong chunkLockNanos = new AtomicLong();

    private static final Map<ResourceKey<Level>, long[]> perLevelNanos = new ConcurrentHashMap<>();
    private static final AtomicLong failures = new AtomicLong();

    /**
     * The cluster tier's shape for the last tick: how many precedence levels
     * the conflict graph needed, how many slices were dispatched across them,
     * and how many entities went through it. Levels are the interesting number
     * -- they are the tier's serial depth, so entities/levels is the width the
     * pool actually had to work with.
     */
    private static final AtomicLong clusterLevels = new AtomicLong();
    private static final AtomicLong clusterTasks = new AtomicLong();
    private static final AtomicLong clusterEntities = new AtomicLong();

    public static void beginTick() {
        lastWorkNanos.set(0);
        clusterLevels.set(0);
        clusterTasks.set(0);
        clusterEntities.set(0);
        chunkHits.set(0);
        chunkMisses.set(0);
        chunkLockNanos.set(0);
        for (Stats stats : PHASES) {
            stats.reset();
        }
    }

    public static void recordChunkAccess(long hits, long misses, long lockNanos) {
        if (hits != 0) {
            chunkHits.addAndGet(hits);
        }
        if (misses != 0) {
            chunkMisses.addAndGet(misses);
        }
        if (lockNanos != 0) {
            chunkLockNanos.addAndGet(lockNanos);
        }
    }

    public static long chunkHits() {
        return chunkHits.get();
    }

    public static long chunkMisses() {
        return chunkMisses.get();
    }

    public static double chunkLockMillis() {
        return chunkLockNanos.get() / 1_000_000.0;
    }

    public static void recordLevel(ResourceKey<Level> dimension, long nanos) {
        lastWorkNanos.addAndGet(nanos);
        // Index 0 is the latest sample, index 1 an exponentially smoothed
        // average -- a full ring buffer per dimension is more memory and more
        // cache traffic than a status command justifies.
        long[] slot = perLevelNanos.computeIfAbsent(dimension, k -> new long[2]);
        synchronized (slot) {
            slot[0] = nanos;
            slot[1] = slot[1] == 0 ? nanos : (slot[1] * 7 + nanos) / 8;
        }
    }

    public static void recordWall(long nanos) {
        lastWallNanos = nanos;
    }

    public static void recordPhaseWork(Phase phase, long nanos) {
        of(phase).work.addAndGet(nanos);
    }

    public static void recordPhaseWall(Phase phase, long nanos) {
        of(phase).wall.addAndGet(nanos);
    }

    public static void recordPhase(Phase phase, int passes, int cells, int parallel, int serial, int deferred) {
        Stats stats = of(phase);
        stats.passes.addAndGet(passes);
        stats.cells.addAndGet(cells);
        stats.parallel.addAndGet(parallel);
        stats.serial.addAndGet(serial);
        stats.deferred.addAndGet(deferred);
    }

    public static void recordCluster(int levels, int tasks, int entities) {
        clusterLevels.addAndGet(levels);
        clusterTasks.addAndGet(tasks);
        clusterEntities.addAndGet(entities);
    }

    public static long clusterLevels() {
        return clusterLevels.get();
    }

    public static long clusterTasks() {
        return clusterTasks.get();
    }

    public static long clusterEntities() {
        return clusterEntities.get();
    }

    public static void recordFailure() {
        failures.incrementAndGet();
    }

    public static double speedup() {
        long wall = lastWallNanos;
        return wall <= 0 ? 0.0 : (double) lastWorkNanos.get() / wall;
    }

    public static long workNanos() {
        return lastWorkNanos.get();
    }

    public static long wallNanos() {
        return lastWallNanos;
    }

    public static long failureCount() {
        return failures.get();
    }

    public static double averageMillis(ResourceKey<Level> dimension) {
        long[] slot = perLevelNanos.get(dimension);
        if (slot == null) {
            return 0.0;
        }
        synchronized (slot) {
            return slot[1] / 1_000_000.0;
        }
    }

    public static void reset() {
        perLevelNanos.clear();
        failures.set(0);
        clusterLevels.set(0);
        clusterTasks.set(0);
        clusterEntities.set(0);
        lastWallNanos = 0;
        lastWorkNanos.set(0);
        for (Stats stats : PHASES) {
            stats.reset();
        }
    }
}
