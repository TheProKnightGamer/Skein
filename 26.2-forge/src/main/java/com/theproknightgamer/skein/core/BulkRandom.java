package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.Skein;
import net.minecraft.util.RandomSource;
import net.minecraft.util.ThreadingDetector;
import net.minecraft.world.level.levelgen.LegacyRandomSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Long runs of draws from one {@code LegacyRandomSource}, taken with its seed
 * in a local.
 *
 * <p>Every draw from a {@code LegacyRandomSource} is a compare-and-swap on an
 * {@code AtomicLong}, there only to catch two threads sharing one source. An
 * explosion takes one draw per ray and one per affected block for the shuffle,
 * some 2,700 in a row, from a source no other thread can be using. On a column
 * of 202 TNT that was more than a fifth of every explosion.
 *
 * <p>This runs the same generator -- the 48-bit linear congruential step and
 * {@code BitRandomSource}'s {@code nextFloat} and {@code nextInt} -- on a copy of
 * the seed, and publishes the final seed with one compare-and-swap. It is used
 * only for the exact class, and only once a self-test at startup has shown it
 * reproduces that class's own output draw for draw, so a mod that changed the
 * generator gets the per-draw path.
 *
 * <p>The final compare-and-swap is not vanilla's per-draw check. That one fails
 * only when two threads collide inside a single draw; this one fails if any
 * other thread drew from the source at any point during the whole run. So a
 * run is taken only from a source {@link #confined} says no other thread can be
 * drawing from, and callers fall back to per-draw calls otherwise.
 */
public final class BulkRandom {
    private BulkRandom() {}

    /** Implemented by {@code LegacyRandomSource}. */
    public interface Seeded {
        AtomicLong skein$seed();
    }

    private static final long MULTIPLIER = 25214903917L;
    private static final long INCREMENT = 11L;
    private static final long MASK = 281474976710655L;
    private static final float FLOAT_MULTIPLIER = 5.9604645E-8F;

    private static final boolean REPRODUCES = selfTest();

    /** Whether the self-test passed, for tests. */
    public static boolean reproduces() {
        return REPRODUCES;
    }

    /**
     * Whether {@code level}'s random source, as a caller on this thread would
     * pick it, belongs to this thread alone: the per-thread source a
     * {@link SkeinRandom} hands out inside a pass, or the level's own source
     * when no pass is open and this thread is the only one in the level. A
     * level whose source is not wrapped -- {@code threadSafeLevelRandom} off --
     * shares it with every cell while a pass is open.
     */
    public static boolean confined(net.minecraft.world.level.Level level) {
        return level.getRandom() instanceof SkeinRandom || !TickContext.deferring();
    }

    /** Whether draws from {@code source} can be taken here. */
    private static boolean bulk(RandomSource source) {
        return REPRODUCES && source.getClass() == LegacyRandomSource.class;
    }

    /** {@code out[i] = source.nextFloat()}, in order. */
    public static void nextFloats(RandomSource source, float[] out) {
        if (!bulk(source)) {
            for (int i = 0; i < out.length; i++) {
                out[i] = source.nextFloat();
            }
            return;
        }
        AtomicLong seed = ((Seeded) source).skein$seed();
        long start = seed.get();
        publish(seed, start, floats(start, out));
    }

    /**
     * {@code Util.shuffle(list, source)}, if {@code source} can be drawn from
     * here; returns false, having done nothing, if not.
     */
    public static boolean shuffle(List<?> list, RandomSource source) {
        if (!bulk(source)) {
            return false;
        }
        AtomicLong seed = ((Seeded) source).skein$seed();
        long start = seed.get();
        publish(seed, start, shuffle(start, list));
        return true;
    }

    /** {@code BitRandomSource.nextFloat} once per entry, from seed {@code s}; returns the seed after. */
    private static long floats(long s, float[] out) {
        for (int i = 0; i < out.length; i++) {
            s = s * MULTIPLIER + INCREMENT & MASK;
            out[i] = (float) (int) (s >> 24) * FLOAT_MULTIPLIER;
        }
        return s;
    }

    /** {@code Util.shuffle} with {@code BitRandomSource.nextInt}, from seed {@code s}; returns the seed after. */
    private static long shuffle(long s, List<?> list) {
        for (int bound = list.size(); bound > 1; bound--) {
            int k;
            if ((bound & bound - 1) == 0) {
                s = s * MULTIPLIER + INCREMENT & MASK;
                k = (int) ((long) bound * (long) (int) (s >> 17) >> 31);
            } else {
                int bits;
                int value;
                do {
                    s = s * MULTIPLIER + INCREMENT & MASK;
                    bits = (int) (s >> 17);
                    value = bits % bound;
                } while (bits - value + (bound - 1) < 0);
                k = value;
            }
            swap(list, bound - 1, k);
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static <T> void swap(List<T> list, int last, int k) {
        list.set(last, list.set(k, list.get(last)));
    }

    private static void publish(AtomicLong seed, long start, long end) {
        if (!seed.compareAndSet(start, end)) {
            throw ThreadingDetector.makeThreadingException("LegacyRandomSource", null);
        }
    }

    /** Draws from a real source against the same draws taken here. */
    private static boolean selfTest() {
        try {
            for (long seed : new long[] {0L, 1L, -7L, 0x5DEECE66DL, 123_456_789_012L}) {
                RandomSource own = new LegacyRandomSource(seed);
                RandomSource bulk = new LegacyRandomSource(seed);
                AtomicLong state = ((Seeded) bulk).skein$seed();
                float[] floats = new float[257];
                long start = state.get();
                publish(state, start, floats(start, floats));
                for (float value : floats) {
                    if (Float.floatToIntBits(value) != Float.floatToIntBits(own.nextFloat())) return fail();
                }
                // Every bound a shuffle of 1,500 uses, powers of two included.
                List<Integer> list = new java.util.ArrayList<>();
                for (int i = 0; i < 1500; i++) list.add(i);
                List<Integer> expected = new java.util.ArrayList<>(list);
                net.minecraft.util.Util.shuffle(expected, own);
                start = state.get();
                publish(state, start, shuffle(start, list));
                if (!list.equals(expected) || own.nextLong() != bulk.nextLong()) return fail();
            }
            return true;
        } catch (RuntimeException | LinkageError e) {
            Skein.LOGGER.warn("Skein: bulk random draws disabled; the self-test could not run", e);
            return false;
        }
    }

    private static boolean fail() {
        Skein.LOGGER.warn("Skein: LegacyRandomSource does not draw as vanilla's does (another mod changed it?); "
                + "explosions take their random draws one at a time");
        return false;
    }
}
