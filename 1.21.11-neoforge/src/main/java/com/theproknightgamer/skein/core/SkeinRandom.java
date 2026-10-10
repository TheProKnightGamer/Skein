package com.theproknightgamer.skein.core;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * {@code Level.random}, made safe for the one window in which more than one
 * thread is inside a level.
 *
 * <p>Vanilla's {@code LegacyRandomSource} keeps its seed in an {@code AtomicLong},
 * so integer and float draws survive concurrency on their own. Its gaussian does
 * not: {@code MarsagliaPolarGaussian} caches the second value of each pair in a
 * pair of plain fields, and two threads drawing at once will hand back the same
 * number or a stale one.
 *
 * <p>Swapping the level's source for a thread-safe implementation fixes the
 * seed and not the gaussian, and changes the server thread's stream for the
 * whole session on the way past. This instead delegates: outside a parallel
 * entity pass every caller gets the level's own source, byte for byte the
 * vanilla object with the vanilla stream, and inside one each thread draws from
 * a source only it can see.
 *
 * <p>Which means the draws inside a pass are no longer one interleaved sequence.
 * They never were a reproducible one -- {@code Level.random} is seeded from the
 * clock at world load -- so what is preserved is the distribution, which is all
 * anything downstream of it depends on.
 */
public final class SkeinRandom implements RandomSource {

    private final RandomSource shared;

    public SkeinRandom(RandomSource shared) {
        this.shared = shared;
    }

    /** The level's own source, for anything that wants vanilla's instance back. */
    public RandomSource shared() {
        return this.shared;
    }

    private RandomSource pick() {
        return TickContext.deferring() ? TickContext.random() : this.shared;
    }

    /**
     * The source this thread's draws come from right now. For a caller about
     * to take many draws in a row with no pass boundary in between, which
     * would otherwise ask the thread context the same question each time.
     */
    public RandomSource current() {
        return pick();
    }

    @Override
    public RandomSource fork() {
        return pick().fork();
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        return pick().forkPositional();
    }

    @Override
    public void setSeed(long seed) {
        // Deliberately the shared source even mid-pass. Seeding is a request for
        // a specific sequence, and answering it with a thread-private stream
        // would quietly discard that.
        this.shared.setSeed(seed);
    }

    @Override
    public int nextInt() {
        return pick().nextInt();
    }

    @Override
    public int nextInt(int bound) {
        return pick().nextInt(bound);
    }

    @Override
    public int nextInt(int origin, int bound) {
        return pick().nextInt(origin, bound);
    }

    @Override
    public int nextIntBetweenInclusive(int min, int max) {
        return pick().nextIntBetweenInclusive(min, max);
    }

    @Override
    public long nextLong() {
        return pick().nextLong();
    }

    @Override
    public boolean nextBoolean() {
        return pick().nextBoolean();
    }

    @Override
    public float nextFloat() {
        return pick().nextFloat();
    }

    @Override
    public double nextDouble() {
        return pick().nextDouble();
    }

    @Override
    public double nextGaussian() {
        return pick().nextGaussian();
    }

    @Override
    public double triangle(double center, double maxDeviation) {
        return pick().triangle(center, maxDeviation);
    }

    @Override
    public void consumeCount(int count) {
        pick().consumeCount(count);
    }
}
