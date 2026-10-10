package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.Skein;

import java.lang.reflect.Method;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
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
 * {@code lithium:options} table, which Radium and SuperChunk's bundled copy
 * read before choosing mixins; Canary reads {@code canary.overrides.properties}
 * from the jar instead, which Skein also ships. The port's own
 * config file still overrides a mod, so the question is not "is Lithium installed" but "are the
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

    /**
     * Lithium itself does not exist for Forge 1.20.1, but two ports of it do,
     * and both carry every conflicting feature below under the same option
     * names: Canary ({@code config/canary.properties}) and Radium
     * ({@code config/lithium.properties}). SuperChunk's relocated copy is the
     * third. Each is recognised by its mixin package.
     */
    private record Port(String mixinRoot, String modId, String configFile, String name) {}

    private static final List<Port> PORTS = List.of(
            new Port("com.abdelaziz.canary.mixin.", "canary", "config/canary.properties", "Canary"),
            new Port("me.jellysquid.mods.lithium.mixin.", "radium", "config/lithium.properties", "Radium"),
            new Port("net.caffeinemc.mods.lithium.mixin.", "lithium", "config/lithium.properties", "Lithium"),
            new Port("dev.superchunk.net.caffeinemc.mods.lithium.mixin.", "superchunk",
                    "config/superchunk.properties", "SuperChunk's Lithium"));


    /**
     * Classes every port patches, the conflicting features' own targets among
     * them. All are transformed by the time the server starts.
     */
    private static final List<String> PROBES = List.of(
            "net.minecraft.world.level.Level", "net.minecraft.server.level.ServerLevel",
            "net.minecraft.world.entity.Entity", "net.minecraft.world.entity.LivingEntity",
            "net.minecraft.world.entity.Mob", "net.minecraft.world.level.chunk.LevelChunk",
            "net.minecraft.world.level.chunk.LevelChunkSection", "net.minecraft.server.level.ChunkMap",
            "net.minecraft.server.level.ServerChunkCache", "net.minecraft.server.level.ChunkHolder",
            "net.minecraft.world.level.border.WorldBorder", "net.minecraft.world.level.block.entity.HopperBlockEntity",
            "net.minecraft.util.ClassInstanceMultiMap", "net.minecraft.world.level.entity.EntitySection",
            "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase",
            "net.minecraft.world.level.block.state.BlockBehaviour", "net.minecraft.world.entity.item.ItemEntity",
            "net.minecraft.world.inventory.AbstractContainerMenu", "net.minecraft.server.MinecraftServer");

    public static boolean installed() {
        return !loaded().isEmpty();
    }

    /** The ports whose mod is loaded. */
    private static List<Port> loaded() {
        List<Port> present = new ArrayList<>();
        net.minecraftforge.fml.ModList mods = net.minecraftforge.fml.ModList.get();
        for (Port port : PORTS) {
            if (mods != null && mods.isLoaded(port.modId())) {
                present.add(port);
            }
        }
        return present;
    }

    /**
     * Finds a port's plugin through the mixins it applied. Mixin 0.8.5's own
     * list of configs ({@code Mixins.getConfigs}) is emptied as the configs
     * are selected at startup, so the applied mixins of a class are the public
     * way back to a config. Used only for the ports with no readable config
     * class of their own (SuperChunk's relocated copy).
     */
    private static IMixinConfigPlugin appliedPlugin(Port port) {
        try {
            for (String probe : PROBES) {
                for (IMixinInfo mixin : Mixins.getMixinsForClass(probe.replace('.', '/'))) {
                    IMixinConfig config = mixin.getConfig();
                    String pkg = config == null ? null : config.getMixinPackage();
                    if (pkg != null && (pkg.endsWith(".") ? pkg : pkg + ".").equals(port.mixinRoot())
                            && config.getPlugin() != null) {
                        return config.getPlugin();
                    }
                }
            }
        } catch (Throwable unreadable) {
            // Treated as unreadable below.
        }
        return null;
    }

    /**
     * Reads a port's effective switch for one conflicting mixin package, the
     * way the port itself decided at startup. Canary keeps Lithium's own
     * {@code CanaryConfig}: {@code load} the properties file it loaded,
     * {@code getEffectiveOptionForMixin}, {@code Option.isEnabled}. Radium's
     * plugin ({@code me.jellysquid.mods.lithium.common.config.LithiumConfig})
     * lives outside its mixin package, so a fresh one can be built and asked
     * through the public plugin API ({@code onLoad}, {@code shouldApplyMixin})
     * -- which also applies its mod overrides. Neither ever answers from a
     * guess: anything that throws is reported as unreadable.
     */
    private interface Reader {
        boolean enabled(Conflict conflict) throws Exception;
    }

    private static Reader reader(Port port) throws Exception {
        ClassLoader loader = LithiumCompat.class.getClassLoader();
        switch (port.modId()) {
            case "canary": {
                Class<?> configClass = Class.forName("com.abdelaziz.canary.common.config.CanaryConfig", true, loader);
                Object config = configClass.getMethod("load", java.io.File.class)
                        .invoke(null, new java.io.File("./config/canary.properties"));
                Method effective = configClass.getMethod("getEffectiveOptionForMixin", String.class);
                Method isEnabled = Class.forName("com.abdelaziz.canary.common.config.Option", true, loader)
                        .getMethod("isEnabled");
                return conflict -> {
                    Object option = effective.invoke(config, conflict.mixin());
                    // No rule at all is Canary's default, which is on.
                    return option == null || (Boolean) isEnabled.invoke(option);
                };
            }
            case "radium": {
                IMixinConfigPlugin plugin = (IMixinConfigPlugin) Class.forName(
                        "me.jellysquid.mods.lithium.common.config.LithiumConfig", true, loader)
                        .getConstructor().newInstance();
                plugin.onLoad(port.mixinRoot().substring(0, port.mixinRoot().length() - 1));
                return conflict -> plugin.shouldApplyMixin("net.minecraft.world.level.Level",
                        port.mixinRoot() + conflict.mixin());
            }
            default: {
                IMixinConfigPlugin plugin = appliedPlugin(port);
                if (plugin == null) {
                    throw new IllegalStateException("no mixin plugin found for " + port.name());
                }
                return conflict -> plugin.shouldApplyMixin("net.minecraft.world.level.Level",
                        port.mixinRoot() + conflict.mixin());
            }
        }
    }

    private static volatile boolean unreadable;

    /** Whether the last {@link #stillEnabled} had to assume everything on because a config could not be read. */
    public static boolean unreadable() {
        return unreadable;
    }

    /**
     * The conflicting features still switched on.
     *
     * @return an empty list when every port present is configured to sit beside Skein
     */
    public static List<Conflict> stillEnabled() {
        List<Conflict> enabled = new ArrayList<>();
        boolean failed = false;
        for (Port port : loaded()) {
            try {
                Reader reader = reader(port);
                for (Conflict conflict : CONFLICTS) {
                    if (!enabled.contains(conflict) && reader.enabled(conflict)) {
                        enabled.add(conflict);
                    }
                }
            } catch (Throwable error) {
                // Unanswerable is on: guessing wrong the other way is silent corruption.
                Skein.LOGGER.warn("Skein: {} is installed but its configuration could not be read ({}), so its "
                        + "per-level caches must be assumed active.", port.name(), error.toString());
                failed = true;
                enabled = new ArrayList<>(CONFLICTS);
                break;
            }
        }
        unreadable = failed;
        return enabled;
    }

    /** Where the port that is present keeps its options. */
    public static String configurationFile() {
        List<Port> present = loaded();
        return present.isEmpty() ? "config/lithium.properties" : present.get(0).configFile();
    }

    private static boolean superChunkInstalled() {
        List<Port> present = loaded();
        return !present.isEmpty() && present.get(0).modId().equals("superchunk");
    }

    /** The name of the Lithium port that is present, for the log. */
    public static String portName() {
        List<Port> present = loaded();
        return present.isEmpty() ? "Lithium" : present.get(0).name();
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
