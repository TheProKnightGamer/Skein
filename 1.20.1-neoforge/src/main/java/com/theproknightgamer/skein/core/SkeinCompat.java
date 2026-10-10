package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;

import java.util.EnumMap;
import java.util.Map;

/**
 * Decides which phases are safe to run given what else is installed.
 *
 * <p>The premise of a single-thread optimisation mod is "only one thread is
 * ever in this level, so cache aggressively and drop the locks". That is a good
 * premise and it is the exact opposite of this one. Where the two meet, the
 * other mod wins: it puts a mutable object on a level-scoped field and hands
 * the same instance to every caller, and Skein hands that instance to sixteen
 * threads.
 *
 * <p>The failure is rarely a crash. Four were found in Lithium alone before
 * this check existed, and every one of them was either silent corruption or a
 * hang:
 *
 * <ul>
 *   <li>{@code alloc.chunk_random} keeps one mutable {@code BlockPos} per level
 *       and returns it from every {@code getBlockRandomPos} in the chunk loop.
 *       Threads overwrite each other's position mid-use, which places blocks in
 *       the wrong chunk and sets fire to places nothing touched.</li>
 *   <li>{@code util.world_border_listener} plus
 *       {@code world.block_entity_ticking.world_border} registers every block
 *       entity in a {@code WeakHashMap} on the world border. Two threads in an
 *       unsynchronised {@code HashMap} corrupt a bucket chain and spin forever.</li>
 *   <li>{@code world.chunk_access} replaces {@code getChunk} with a cache on the
 *       chunk source that every thread in the level would share.</li>
 *   <li>the hopper inventory tracker calls
 *       {@code EntitySectionStorage.getOrCreateSection} from a block entity
 *       tick -- a structural write to the very index Skein makes read-only for
 *       the duration of a pass.</li>
 * </ul>
 *
 * <p>A later collision stress test also exposed concurrent lazy writes in
 * Lithium's entity-class-group cache. The known conflicting options gate all
 * in-level phases; disabling those options allows both mods to run. Dimension
 * threading follows its own configuration and compatibility limits.
 */
public final class SkeinCompat {
    private SkeinCompat() {}

    private static final Map<TickMetrics.Phase, String> BLOCKED = new EnumMap<>(TickMetrics.Phase.class);

    private static final TickMetrics.Phase[] IN_LEVEL = {
            TickMetrics.Phase.SCHEDULED_TICKS,
            TickMetrics.Phase.RANDOM_TICKS,
            TickMetrics.Phase.ENTITIES,
            TickMetrics.Phase.BLOCK_ENTITIES
    };

    /** True when {@code phase} is safe to run given what else is installed. */
    public static boolean allows(TickMetrics.Phase phase) {
        return BLOCKED.isEmpty() || !BLOCKED.containsKey(phase);
    }

    public static String reason(TickMetrics.Phase phase) {
        return BLOCKED.get(phase);
    }

    public static boolean any() {
        return !BLOCKED.isEmpty();
    }

    /** Re-runs the check. Called once the server exists and every mod is loaded. */
    public static void scan() {
        BLOCKED.clear();

        String hostile = hostileMod();
        if (hostile == null) {
            return;
        }
        if (LithiumCompat.installed()) {
            java.util.List<LithiumCompat.Conflict> enabled = LithiumCompat.stillEnabled();
            if (enabled.isEmpty()) {
                // Every feature that cannot survive more than one thread in a
                // level has been switched off, so the two mods want different
                // halves of the problem and can have them: Lithium makes the
                // work cheaper, Skein spreads it.
                Skein.LOGGER.info("Skein: " + LithiumCompat.portName() + " is installed and the features that conflict with in-level "
                        + "threading are all disabled, so both are running. Skein spreads the work; Lithium "
                        + "makes it cheaper.");
                return;
            }
            if (!SkeinConfig.Live.ignoreModConflicts) {
                String how = "Skein: " + LithiumCompat.portName() + " is installed and " + enabled.size() + " of its features cache "
                        + "per-level state that in-level threading cannot survive, so scheduled ticks, random "
                        + "ticks, entities and block entities are off. To use both, add these to "
                        + LithiumCompat.configurationFile()
                        + (LithiumCompat.unreadable()
                                ? ", set general.ignoreModConflicts = true in config/skein-common.toml (Skein could not "
                                        + "read that file to confirm them),"
                                : "")
                        + " and restart:"
                        + LithiumCompat.instructions(enabled)
                        + "\n  The remaining Lithium optimisations stay enabled. Dimension threading follows "
                        + "its own settings and compatibility limits.";
                for (TickMetrics.Phase phase : IN_LEVEL) {
                    BLOCKED.put(phase, how);
                }
                Skein.LOGGER.warn("{}", how);
                return;
            }
        }
        if (SkeinConfig.Live.ignoreModConflicts) {
            Skein.LOGGER.warn("Skein: {} is installed and caches per-level state on hot tick paths, which the "
                            + "in-level phases are not safe next to. general.ignoreModConflicts is on, so they are "
                            + "running anyway. Expect blocks placed in the wrong chunk, fires nothing lit, and "
                            + "hangs inside an unsynchronised map; a stalled phase dumps every worker's stack "
                            + "after ten seconds.", hostile);
            return;
        }

        String reason = hostile + " caches per-level state on hot tick paths -- a shared mutable BlockPos in the "
                + "chunk loop, a WeakHashMap of block entities on the world border, a chunk cache on the chunk "
                + "source, entity sections created from hopper ticks. Each of those is silent corruption or a "
                + "hang rather than a crash, and there is no way to test for the ones nobody has found yet. "
                + "Dimension threading still runs: it gives each level one thread, which is the assumption those "
                + "caches are built on. Set general.ignoreModConflicts=true to run the in-level phases anyway.";
        for (TickMetrics.Phase phase : IN_LEVEL) {
            BLOCKED.put(phase, reason);
        }
        Skein.LOGGER.warn("Skein: {} detected; scheduled ticks, random ticks, entities and block entities are off.",
                hostile);
        Skein.LOGGER.warn("Skein: {}", reason);
    }

    /**
     * The name of an installed mod whose optimisations assume one thread per
     * level, or null.
     */
    private static String hostileMod() {
        if (LithiumCompat.installed()) {
            return LithiumCompat.portName();
        }
        return null;
    }

}
