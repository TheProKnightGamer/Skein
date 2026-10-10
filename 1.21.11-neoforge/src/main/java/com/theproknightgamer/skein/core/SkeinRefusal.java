package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.Skein;

/**
 * A request Skein turned down because serving it on this thread would race: a
 * chunk load from a parallel tick, or one level's tick reaching into another
 * level while both are ticking.
 *
 * <p>It cuts short the one tick that asked and nothing else. Left to vanilla's
 * handlers it would be reported as that entity or block entity crashing:
 * deleted under NeoForge's {@code removeErroringEntities} and
 * {@code removeErroringBlockEntities}, a quarantined dimension, or -- under
 * {@code crashPolicy = RETHROW} -- a stopped server, over a request vanilla
 * serves routinely. So every place that catches a tick's failure lets a
 * refusal through as what it is.
 */
public final class SkeinRefusal extends IllegalStateException {
    public SkeinRefusal(String message) {
        super(message);
    }

    /** Whether {@code thrown}, or anything it was wrapped in on the way up, is a refusal. */
    public static boolean in(Throwable thrown) {
        for (int depth = 0; thrown != null && depth < 32; depth++, thrown = thrown.getCause()) {
            if (thrown instanceof SkeinRefusal) {
                return true;
            }
        }
        return false;
    }

    /** The refusal already warned where it was made, at most every few seconds. */
    public static void swallowed(Object asker, Throwable refusal) {
        Skein.LOGGER.debug("Skein: cut the tick of {} short: {}", asker, refusal.getMessage());
    }
}
