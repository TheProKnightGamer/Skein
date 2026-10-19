package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.SkeinConfig;
import net.minecraft.util.Util;

/**
 * Decides, per phase and per level, whether going parallel is actually worth it
 * this tick.
 *
 * <h2>Why a threshold on item count is not enough</h2>
 *
 * <p>Every phase files all of its work before it can decide how to split it, and
 * a split costs a barrier, a {@link TickContext} bracket per cell, and a replay
 * of whatever the pass deferred. On a busy level that vanishes into the win. On a
 * quiet one it is the whole cost: measured on a world of two thousand hoppers and
 * a hundred mobs, the phases saved 0.65ms of work and the machinery around them
 * cost more than that, leaving the tick 0.31ms <em>slower</em> than with the mod
 * switched off.
 *
 * <p>The existing {@code minEntities}/{@code minTickers} gates cannot fix that,
 * because they are counts and the thing that matters is time. Two thousand
 * hoppers clear the count gate comfortably and still do not add up to enough
 * work to pay for a barrier.
 *
 * <h2>What this does instead</h2>
 *
 * <p>It measures both. Each phase is timed end to end -- filing, barriers,
 * deferral replay and all -- and the result goes into one of two running
 * averages, depending on which way it ran. The phase then takes whichever path
 * has been cheaper, and periodically it deliberately takes
 * the other one for a single tick to keep that estimate honest. A world that
 * gets busier is noticed within a few seconds; a world that goes quiet stops
 * paying for threads it does not need.
 *
 * <p>Parallel has to be beating serial by a clear margin, not merely tying, so
 * that a phase sitting near the crossover does not flip every probe -- and
 * because when the two are equal the serial path is the better one anyway: it
 * has no barrier for the rest of the tick to wait on and no deferred writes to
 * replay.
 */
public final class PhaseGovernor {

    private static final int PHASES = TickMetrics.Phase.values().length;

    /**
     * How often to re-time the path not in use, in ticks, as a function of how
     * decisive the gap is.
     *
     * <p>A probe costs one tick on the slower path, so on a busy level a fixed
     * interval buys an accurate estimate at the price of a visible hitch every
     * few seconds. Scaling the interval with the ratio between the two costs
     * fixes that and has a tidy property: the probe costs roughly the slow
     * path's time once per {@code BASE x ratio} ticks, which amortises to about
     * {@code 1/BASE} of the fast path however far apart they are. Close
     * together, probing is cheap and worth doing often; far apart, it is
     * expensive and there is nothing to learn.
     */
    private static final int PROBE_BASE = 100;
    private static final int PROBE_MIN = 100;
    private static final int PROBE_MAX = 2400;

    /** Exponential moving average weight, as a right shift: 1/8 of each new sample. */
    private static final int SMOOTHING = 3;

    /**
     * The most one tick on the path in use may count for, as a multiple of its
     * average: a garbage collection or a burst of chunk loading inside one
     * phase says nothing about the path, and unclamped it could flip the
     * choice on its own. A real change in load still comes through within a
     * few ticks, since each one may raise the average by up to {@code (CLAMP
     * - 1) / 8}.
     */
    private static final int CLAMP = 4;

    private final long[] parallelNanos = new long[PHASES];
    private final long[] serialNanos = new long[PHASES];
    private final int[] sinceProbe = new int[PHASES];
    /** Probes in a row that confirmed the path in use; each one doubles the wait for the next. */
    private final int[] confirmed = new int[PHASES];
    private final boolean[] parallel = new boolean[PHASES];
    /** Whether this tick took the other path to re-time it, rather than the one preferred. */
    private final boolean[] probing = new boolean[PHASES];
    private final long[] started = new long[PHASES];
    // Workers can announce a cost discontinuity during this tick. The next
    // entity phase begins only after every worker/barrier from this one closes.
    private volatile boolean entityBurst;

    /** Upcoming TNT detonation: countdown timings do not describe explosion work. */
    public void requestEntityBurstProbe() {
        this.entityBurst = true;
    }

    /**
     * Opens the phase and returns the path it should take.
     *
     * @param eligible whether the parallel path is permitted at all -- config,
     *                 mod conflicts, the profiler. An ineligible phase is still
     *                 timed, so that if it becomes eligible later there is
     *                 already a serial figure to compare against.
     */
    public boolean begin(TickMetrics.Phase phase, boolean eligible) {
        int i = phase.ordinal();
        this.started[i] = Util.getNanos();
        boolean burst = phase == TickMetrics.Phase.ENTITIES && this.entityBurst;
        if (phase == TickMetrics.Phase.ENTITIES) this.entityBurst = false;
        // A hint only bypasses the historical cost estimate, never eligibility
        // or the partition's admission/geometry rules.
        this.probing[i] = false;
        this.parallel[i] = eligible && (burst || wantsParallel(i));
        if (burst && this.parallel[i] && !prefersParallel(i)) {
            this.probing[i] = true;
        }
        return this.parallel[i];
    }

    /** The decision {@link #begin} made, for the rest of this tick. */
    public boolean parallel(TickMetrics.Phase phase) {
        return this.parallel[phase.ordinal()];
    }

    /**
     * Closes the phase and folds its cost into the average for the path it took.
     *
     * <p>A probe replaces the other path's figure outright instead of moving it
     * an eighth of the way. That figure was last brought up to date a probe
     * interval ago -- up to two minutes -- and is the only thing standing
     * between the level and the faster path: eased in an eighth at a time, one
     * bad sample (the first, cold-JIT parallel tick of a phase; a parallel tick
     * while the machine was busy with something else) kept the in-level phases
     * serial for minutes after the server started. Measured on a loaded
     * machine, 1440 mobs ran serially at 30-50ms a tick for a minute and a half
     * while the parallel path, once re-timed, took 24ms.
     */
    public void finish(TickMetrics.Phase phase) {
        int i = phase.ordinal();
        long elapsed = Util.getNanos() - this.started[i];
        if (elapsed < 0) {
            return;
        }
        long[] into = this.parallel[i] ? this.parallelNanos : this.serialNanos;
        if (into[i] == 0 || this.probing[i]) {
            into[i] = Math.max(1L, elapsed);
        } else {
            long sample = Math.min(elapsed, into[i] * CLAMP);
            into[i] = into[i] + ((sample - into[i]) >> SMOOTHING);
        }
        if (this.probing[i]) {
            // Back off only while probes keep agreeing with the choice; one that
            // overturns it starts the next interval from the minimum again.
            this.probing[i] = false;
            this.confirmed[i] = prefersParallel(i) == this.parallel[i] ? 0 : this.confirmed[i] + 1;
        }
    }

    public long parallelNanos(TickMetrics.Phase phase) {
        return this.parallelNanos[phase.ordinal()];
    }

    public long serialNanos(TickMetrics.Phase phase) {
        return this.serialNanos[phase.ordinal()];
    }

    /**
     * The ratio rule above, held back to {@code PROBE_MIN << confirmed}: until
     * probes have agreed with the choice a few times in a row, the figures
     * behind a large ratio may be one bad sample, and waiting two minutes to
     * find out costs two minutes on the slower path.
     */
    private static int probeInterval(long a, long b, int confirmed) {
        long hi = Math.max(a, b);
        long lo = Math.max(1L, Math.min(a, b));
        long ratio = Math.max(1L, hi / lo);
        long byRatio = Math.min(PROBE_MAX, Math.max(PROBE_MIN, PROBE_BASE * ratio));
        return (int) Math.min(byRatio, (long) PROBE_MIN << Math.min(confirmed, 5));
    }

    /** The cheaper path by the current figures, with the margin parallel has to clear. */
    private boolean prefersParallel(int i) {
        long p = this.parallelNanos[i];
        long s = this.serialNanos[i];
        if (p == 0 || s == 0) {
            // Nothing to compare yet: take the path we have not measured.
            return p == 0;
        }
        return p + (p >> 4) < s;
    }

    private boolean wantsParallel(int i) {
        if (!SkeinConfig.Live.adaptive) {
            return true;
        }
        boolean prefer = prefersParallel(i);
        long p = this.parallelNanos[i];
        long s = this.serialNanos[i];
        if (p != 0 && s != 0 && ++this.sinceProbe[i] >= probeInterval(p, s, this.confirmed[i])) {
            this.sinceProbe[i] = 0;
            this.probing[i] = true;
            return !prefer;
        }
        return prefer;
    }
}
