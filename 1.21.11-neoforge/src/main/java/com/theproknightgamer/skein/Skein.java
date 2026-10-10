package com.theproknightgamer.skein;

import com.theproknightgamer.skein.core.Demotions;
import com.theproknightgamer.skein.core.DimensionTicker;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.SkeinCompat;
import com.theproknightgamer.skein.core.SkeinPool;
import com.theproknightgamer.skein.core.TickContext;
import com.theproknightgamer.skein.core.TickMetrics;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.List;

/**
 * Skein ticks Minecraft's dimensions -- and optionally the entities inside
 * them -- on more than one thread, then joins them back together before the
 * server tick ends.
 *
 * <p>The mod owns almost no game state. Its whole job is to decide what may run
 * concurrently, hand that work to {@link SkeinPool}, and hold a barrier so the
 * rest of the server never observes a partially ticked world. Everything that
 * makes that safe lives in {@code com.theproknightgamer.skein.mixin}.
 */
@Mod(Skein.MOD_ID)
public final class Skein {
    public static final String MOD_ID = "skein";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static volatile SkeinPool pool;
    private static volatile MinecraftServer server;

    public Skein(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, SkeinConfig.SPEC);

        modBus.addListener(ModConfigEvent.Loading.class, event -> onConfigLoaded());
        modBus.addListener(ModConfigEvent.Reloading.class, event -> onConfig());
        // A hook found missing after startup -- a target class loaded late --
        // re-masks the snapshot at the top of the next tick.
        Hooks.onChange(SkeinConfig::markStale);

        NeoForge.EVENT_BUS.addListener(ServerStartingEvent.class, Skein::onServerStarting);
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, Skein::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> TickContext.unbindServerThread());
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, SkeinCommands::register);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Pre.class, Skein::onServerTickPre);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, Skein::onServerTick);
    }

    /**
     * A config reload arrives on NightConfig's file-watcher thread, which is no
     * place to rebuild a snapshot a tick is reading, so it is only noted here
     * and taken by the server thread at the top of the next tick.
     */
    private static void onConfig() {
        SkeinConfig.markStale();
    }

    /**
     * The first load happens while mods are still being set up, before any
     * level exists, so it is taken at once. Levels are built before the server
     * starting event, and they read the snapshot as they are built -- whether
     * to wrap the level's random source, for one -- so waiting for that event
     * would hand them the defaults instead of the file.
     */
    private static void onConfigLoaded() {
        if (server == null) {
            SkeinConfig.refresh();
        } else {
            SkeinConfig.markStale();
        }
    }

    private static void onServerStarting(ServerStartingEvent event) {
        server = event.getServer();
        // This event runs on the server thread, which is the identity every
        // ownership check in TickContext is measured against.
        TickContext.bindServerThread(Thread.currentThread());
        // Before the snapshot is taken, so that every hook has been checked and
        // the snapshot already leaves out whatever a missing one switched off.
        Hooks.loadTargets(Skein.class.getClassLoader());
        SkeinConfig.refresh();
        DimensionTicker.clearQuarantine();
        TickMetrics.reset();

        SkeinCompat.scan();
        List<String> unknown = SkeinConfig.resolveRegistries();
        if (!unknown.isEmpty()) {
            LOGGER.warn("Skein: blockEntities.blacklist names {} which nothing is registered under; ignoring", unknown);
        }
        // Types that threw or hung in parallel on an earlier run.
        Demotions.load();

        boolean lost = Hooks.missing().stream().anyMatch(hook -> !hook.yielded() && !hook.features().isEmpty());
        if (!lost) {
            // Nothing missing; anything off was left to another mod on purpose.
            LOGGER.info("Skein: {}", Hooks.summary());
        } else {
            LOGGER.warn("Skein: {}. The server runs on; the log above names each missing hook and the other "
                    + "mods that changed the same class.", Hooks.summary());
        }

        int threads = SkeinConfig.resolveThreadCount();
        pool = new SkeinPool(threads);
        LOGGER.info("Skein: {} worker threads; dimensions {}, scheduled ticks {}, random ticks {}, "
                        + "entities {}, block entities {}; {}-chunk cells, {}-block margin",
                threads,
                SkeinConfig.Live.dimensions ? "on" : "off",
                state(SkeinConfig.Live.scheduledTicks, TickMetrics.Phase.SCHEDULED_TICKS),
                state(SkeinConfig.Live.randomTicks, TickMetrics.Phase.RANDOM_TICKS),
                state(SkeinConfig.Live.entities, TickMetrics.Phase.ENTITIES),
                state(SkeinConfig.Live.blockEntities, TickMetrics.Phase.BLOCK_ENTITIES),
                SkeinConfig.Live.regionChunks,
                SkeinConfig.Live.borderMargin);
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        SkeinPool current = pool;
        // Cleared before shutdown, so a tick racing the stop falls through to
        // the vanilla path instead of submitting to a dying pool.
        pool = null;
        server = null;
        if (current != null) {
            current.shutdown();
        }
    }

    /**
     * Zeroes the per-tick counters. Here rather than inside the dimension phase
     * because the entity phase runs whether or not dimensions are parallel, and
     * counters that are only cleared by the other half read as lifetime totals.
     *
     * <p>Also where a config change from the watcher thread is taken up: this
     * fires on the server thread, before the levels tick, so nothing can be
     * reading the snapshot while it is rebuilt.
     */
    private static void onServerTickPre(ServerTickEvent.Pre event) {
        SkeinConfig.refreshIfStale();
        TickMetrics.beginTick();
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        int every = SkeinConfig.Live.logEveryTicks;
        if (every <= 0 || pool == null) {
            return;
        }
        if (event.getServer().getTickCount() % every != 0) {
            return;
        }
        StringBuilder line = new StringBuilder(String.format(
                "Skein: %.2fx dimension overlap (%.2fms of level work in %.2fms wall)",
                TickMetrics.speedup(),
                TickMetrics.workNanos() / 1_000_000.0,
                TickMetrics.wallNanos() / 1_000_000.0));
        for (TickMetrics.Phase phase : TickMetrics.Phase.values()) {
            TickMetrics.Stats stats = TickMetrics.of(phase);
            if (!stats.ran()) {
                continue;
            }
            line.append(String.format("; %s %.2fx (%.2fms in %.2fms, %d cells over %d passes, "
                            + "%d parallel / %d serial, %d deferred)",
                    phase.label(), stats.overlap(), stats.workMillis(), stats.wallMillis(),
                    stats.cells(), stats.passes(), stats.parallel(), stats.serial(), stats.deferred()));
        }
        if (TickMetrics.chunkHits() > 0 || TickMetrics.chunkMisses() > 0) {
            line.append(String.format("; chunks %d hit / %d miss, %.2fms waiting on the chunk lock",
                    TickMetrics.chunkHits(), TickMetrics.chunkMisses(), TickMetrics.chunkLockMillis()));
        }
        LOGGER.info(line.toString());
    }

    /** Whether a phase will actually run: on in the config, and not blocked by another mod. */
    private static String state(boolean configured, TickMetrics.Phase phase) {
        if (!configured) {
            return "off";
        }
        return SkeinCompat.allows(phase) ? "on" : "off (blocked by another mod)";
    }

    /** The worker pool, or null when no server is running. */
    public static SkeinPool pool() {
        return pool;
    }

    public static MinecraftServer server() {
        return server;
    }
}
