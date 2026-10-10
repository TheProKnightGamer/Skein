package com.theproknightgamer.skein.compat;

import java.util.Locale;

/** Wall-clock split of the server tick, accumulated by the test mod's timing mixins. */
public final class PhaseTimes {
    private PhaseTimes() {}

    public enum Part { CHUNK_WALK, CHUNK_CENSUS, CHUNK_LOOP, CHUNK_BROADCAST, TRACKING, ENTITIES, FUNCTIONS, CHUNK_SEND, CHILDREN }

    private static final long[] total = new long[Part.values().length];
    private static final long[] open = new long[Part.values().length];
    public static volatile boolean enabled;

    /** Only the overworld on the thread that owns it is measured, so nested levels do not double-count. */
    public static void begin(Part part) {
        if (enabled) open[part.ordinal()] = System.nanoTime();
    }

    public static void end(Part part) {
        if (enabled && open[part.ordinal()] != 0) {
            total[part.ordinal()] += System.nanoTime() - open[part.ordinal()];
            open[part.ordinal()] = 0;
        }
    }

    /** Ends one part and begins the next at the same instant. */
    public static void next(Part ending, Part starting) {
        if (!enabled) return;
        long now = System.nanoTime();
        if (open[ending.ordinal()] != 0) total[ending.ordinal()] += now - open[ending.ordinal()];
        open[ending.ordinal()] = 0;
        open[starting.ordinal()] = now;
    }

    public static String report(int ticks) {
        StringBuilder out = new StringBuilder();
        for (Part part : Part.values()) {
            out.append(String.format(Locale.ROOT, "%s %.3f ms; ", part.name().toLowerCase(Locale.ROOT),
                    total[part.ordinal()] / 1e6 / Math.max(1, ticks)));
        }
        return out.toString();
    }
}
