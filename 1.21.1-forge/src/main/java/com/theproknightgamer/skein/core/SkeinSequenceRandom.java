package com.theproknightgamer.skein.core;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

import java.util.concurrent.locks.ReentrantLock;

/**
 * A loot table's random sequence, made safe to draw from while several cells
 * are rolling loot at once.
 *
 * <p>Every loot table the game ships carries a {@code random_sequence}, so a
 * decaying leaf, a mob burning at sunrise and a villager harvesting wheat all
 * end up drawing from one {@code XoroshiroRandomSource} per table id -- and one
 * for the whole server rather than one per level, because the overworld's
 * {@code RandomSequences} is handed to every other dimension when it is built.
 * Its {@code nextLong} is a read-modify-write of two plain longs, so two
 * threads rolling the same table at the same moment read the same state, hand
 * each other the same number, and leave the sequence somewhere a serial run
 * could never have put it.
 *
 * <p>{@link SkeinRandom}'s answer -- a stream per thread -- is the wrong one
 * here. A random sequence exists precisely so that loot is reproducible from
 * the save, which is what {@code /random} and the world-seed salt are for, and
 * a thread-private source would quietly throw that away. So every draw is a
 * real draw from the real sequence, taken under a lock; the only thing that
 * changes is the order the draws happen in, which is what running cells at the
 * same time changes anyway.
 *
 * <p>The lock is a leaf: nothing under it reads the world or takes another
 * lock, so it cannot participate in a cycle with the point-of-interest or
 * chunk-load locks.
 */
public final class SkeinSequenceRandom implements RandomSource {

    private final RandomSource sequence;
    private final ReentrantLock lock;

    public SkeinSequenceRandom(RandomSource sequence, ReentrantLock lock) {
        this.sequence = sequence;
        this.lock = lock;
    }

    @Override
    public RandomSource fork() {
        this.lock.lock();
        try {
            return this.sequence.fork();
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        this.lock.lock();
        try {
            return this.sequence.forkPositional();
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public void setSeed(long seed) {
        this.lock.lock();
        try {
            this.sequence.setSeed(seed);
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public int nextInt() {
        this.lock.lock();
        try {
            return this.sequence.nextInt();
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public int nextInt(int bound) {
        this.lock.lock();
        try {
            return this.sequence.nextInt(bound);
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public long nextLong() {
        this.lock.lock();
        try {
            return this.sequence.nextLong();
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public boolean nextBoolean() {
        this.lock.lock();
        try {
            return this.sequence.nextBoolean();
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public float nextFloat() {
        this.lock.lock();
        try {
            return this.sequence.nextFloat();
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public double nextDouble() {
        this.lock.lock();
        try {
            return this.sequence.nextDouble();
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public double nextGaussian() {
        this.lock.lock();
        try {
            return this.sequence.nextGaussian();
        } finally {
            this.lock.unlock();
        }
    }
}
