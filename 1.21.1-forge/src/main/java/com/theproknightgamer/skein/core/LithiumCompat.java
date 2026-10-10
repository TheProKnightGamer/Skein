package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.Skein;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Works out whether Lithium has been configured so that Skein can run beside it.
 *
 * <h2>Why this is worth doing</h2>
 *
 * <p>The two mods optimise opposite halves of the same problem and it costs real
 * time to have to choose. Profiling a busy farm put {@code Level.getEntities} at
 * 45% of all worker time and 18% of the coordinator's -- walking a
 * {@code LongAVLTreeSet} of sections and collecting through a stream buffer,
 * once per projectile per tick. Skein now narrows ordinary snowball/egg queries
 * inside threaded passes; Lithium optimises broader query and game-logic paths,
 * including serial work where threading does not pay.
 *
 * <h2>Why it cannot just be allowed</h2>
 *
 * <p>Lithium's premise is one thread per level, and several of its features cache
 * mutable state accordingly. Tests have exposed the following conflicts:
 *
 * <ul>
 *   <li>{@code alloc.chunk_random} keeps one mutable {@code BlockPos} per level
 *       and hands it to every {@code getBlockRandomPos} in the chunk loop.
 *       Blocks end up placed in the wrong chunk -- amethyst and bamboo
 *       "teleporting" -- and lava lights fires where nothing touched.</li>
 *   <li>{@code util.world_border_listener}, with
 *       {@code world.block_entity_ticking.world_border}, registers every block
 *       entity in a {@code WeakHashMap} on the world border. Two threads inside
 *       it corrupt a bucket chain and spin forever.</li>
 *   <li>{@code world.chunk_access} puts a chunk cache on the chunk source, and
 *       {@code @Overwrite}s {@code getChunk} while doing it.</li>
 *   <li>{@code block.hopper} creates entity sections from a block entity tick --
 *       a structural write to the index a pass makes read-only.</li>
 *   <li>{@code chunk.entity_class_groups} lazily populates a shared array map
 *       during collision queries; concurrent first queries corrupt that map.</li>
 *   <li>{@code entity.inactive_navigations} mutates a per-level navigation set
 *       from mob ticks; {@code entity.collisions.unpushable_cramming} lazily
 *       changes a shared section mask while querying collisions.</li>
 *   <li>{@code util.block_entity_retrieval} recognizes only the original world
 *       thread, silently hiding inventories from cell workers.</li>
 * </ul>
 *
 * <h2>What this does</h2>
 *
 * <p>Each of those is a separately switchable Lithium option, and Skein's
 * {@code mods.toml} switches all of them off through Lithium's
 * {@code lithium:options} table, which standalone Lithium and SuperChunk's
 * bundled copy both read before choosing mixins. Lithium's own config file still
 * overrides a mod, so the question is not "is Lithium installed" but "are the
 * conflicting features off". Rather than parse
 * {@code lithium.properties} here -- which would mean reimplementing Lithium's
 * precedence rules, where disabling a parent package disables its children --
 * this asks Lithium's actual startup {@code LithiumConfig} what the effective option for a
 * representative mixin is. If it answers "disabled" for all of them, the
 * in-level phases run and you get both mods. The standalone implementation takes
 * precedence over SuperChunk's relocated copy, which stands down in that case.
 *
 * <p>Anything unreadable is treated as still-enabled. The failure mode of
 * guessing wrong in the other direction is silent world corruption.
 *
 * <p>These are the known, individually switchable conflicts. Further mixins
 * can still introduce shared state and need their own verification.
 */
public final class LithiumCompat {
    private LithiumCompat() {}

    private static final String[] ROOTS = {"net.caffeinemc.mods.lithium", "dev.superchunk.net.caffeinemc.mods.lithium"};

    /** One conflicting feature: the option that switches it, and a mixin inside it. */
    public record Conflict(String option, String mixin) {}

    private static final List<Conflict> CONFLICTS = List.of(
            new Conflict("mixin.alloc.chunk_random",
                    "alloc.chunk_random.LevelMixin"),
            new Conflict("mixin.util.world_border_listener",
                    "util.world_border_listener.WorldBorderMixin"),
            new Conflict("mixin.world.block_entity_ticking.world_border",
                    "world.block_entity_ticking.world_border.DirectBlockEntityTickInvokerMixin"),
            new Conflict("mixin.world.chunk_access",
                    "world.chunk_access.ChunkHolderMixin"),
            new Conflict("mixin.block.hopper",
                    "block.hopper.AbstractContainerMenuMixin"),
            new Conflict("mixin.chunk.entity_class_groups",
                    "chunk.entity_class_groups.ClassInstanceMultiMapMixin"),
            new Conflict("mixin.entity.inactive_navigations",
                    "entity.inactive_navigations.ServerLevelMixin"),
            new Conflict("mixin.entity.collisions.unpushable_cramming",
                    "entity.collisions.unpushable_cramming.EntitySectionMixin"),
            new Conflict("mixin.util.block_entity_retrieval",
                    "util.block_entity_retrieval.LevelMixin"));

    public static boolean installed() {
        return activeRoot() != null;
    }

    private static String activeRoot() {
        // SuperChunk stands its relocated mixins down when standalone Lithium
        // is present. Inspect the implementation that actually transforms classes.
        for (String root : ROOTS) {
            try {
                Class.forName(root + ".common.config.LithiumConfig", false, LithiumCompat.class.getClassLoader());
                return root;
            } catch (ClassNotFoundException absent) {
                // Try the bundled implementation.
            }
        }
        return null;
    }

    /**
     * The conflicting features still switched on.
     *
     * @return an empty list when Lithium is configured to sit beside Skein
     */
    public static List<Conflict> stillEnabled() {
        try {
            String root = activeRoot();
            if (root == null) return List.of();
            Class<?> configClass = Class.forName(root + ".common.config.LithiumConfig", true, LithiumCompat.class.getClassLoader());
            Method effectiveOption = configClass.getMethod("getEffectiveOptionForMixin", String.class);
            Method isEnabled = Class.forName(root + ".common.config.Option", true, LithiumCompat.class.getClassLoader())
                    .getMethod("isEnabled");

            // Re-reading the properties file can claim a mixin is disabled even
            // though it was applied at startup. Inspect the boot snapshot instead.
            Class<?> plugin = Class.forName(root + ".mixin.LithiumMixinPlugin", false, LithiumCompat.class.getClassLoader());
            Field field = plugin.getDeclaredField("CONFIG");
            if (!field.trySetAccessible()) throw new IllegalStateException("Lithium startup config is inaccessible");
            Object config = field.get(null);
            if (config == null) throw new IllegalStateException("Lithium startup config is missing");
            List<Conflict> enabled = new ArrayList<>();
            for (Conflict conflict : CONFLICTS) {
                // Relative to Lithium's mixin package root: getMixinRuleName
                // simply prefixes "mixin.", so a fully qualified name would
                // build keys like "mixin.net.caffeinemc..." and match nothing.
                Object option = effectiveOption.invoke(config, conflict.mixin());
                // No rule at all means Lithium's default, which is on.
                if (option == null || (Boolean) isEnabled.invoke(option)) {
                    enabled.add(conflict);
                }
            }
            return enabled;
        } catch (Throwable unreadable) {
            Skein.LOGGER.warn("Skein: Lithium is installed but its configuration could not be read ({}), "
                    + "so its per-level caches must be assumed active.", unreadable.toString());
            return CONFLICTS;
        }
    }

    /** SuperChunk regenerates lithium.properties; direct edits there do not survive startup. */
    public static String configurationFile() {
        return superChunkInstalled() ? "config/superchunk.properties" : "config/lithium.properties";
    }

    private static boolean superChunkInstalled() {
        return net.minecraftforge.fml.ModList.get().isLoaded("superchunk");
    }

    /** The lines to add to the owning configuration, for the log. */
    public static String instructions(List<Conflict> enabled) {
        StringBuilder lines = new StringBuilder();
        for (Conflict conflict : enabled) {
            String option = superChunkInstalled()
                    ? "lithium." + conflict.option().substring("mixin.".length()) : conflict.option();
            lines.append("\n    ").append(option).append("=false");
        }
        return lines.toString();
    }

}
