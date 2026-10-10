package com.theproknightgamer.skein.compat;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.TickMetrics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A run with mods that fight Skein for the same code ({@code -PconflictTest}).
 *
 * <p>The probes in {@code compat.mixin.Conflict*} stand in for four kinds of
 * other mod, each at the default priority:
 *
 * <ul>
 *   <li>one that overwrites a method Skein hooks, so the hook cannot land --
 *       the game must start, report the hook, and switch off only the feature
 *       that needed it;</li>
 *   <li>one that {@code @Redirect}s a call Skein also takes over -- both must
 *       apply;</li>
 *   <li>one that hooks the entity-tracking loop inside the stretch Skein would
 *       run on the pool -- Skein must leave that loop alone;</li>
 *   <li>one that wraps each block entity's tick in the loop -- its wrapper
 *       must stay on the level thread, outside Skein's, and see every ticker,
 *       including the ones that then tick in parallel.</li>
 * </ul>
 */
public final class ConflictVerification {
    private static final int FURNACES_PER_CELL = 60;
    private static final int CELLS = 4;

    private static final AtomicInteger wrapped = new AtomicInteger();
    private static final AtomicInteger wrappedInPass = new AtomicInteger();
    private static boolean started;
    private static int ticks;
    private static int parallelTicks;

    ConflictVerification() {
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, ConflictVerification::start);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Pre.class, event -> wrapped.set(0));
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, ConflictVerification::afterTick);
    }

    /** Called by the block-entity probe for every ticker it wraps. */
    public static void wrapped(boolean insidePass) {
        wrapped.incrementAndGet();
        if (insidePass) {
            wrappedInPass.incrementAndGet();
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static void start(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        List<Hooks.Missing> missing = Hooks.missing();
        Skein.LOGGER.info("Conflict run: {}; {}", Hooks.summary(), missing);

        // An overwritten method: reported, and only its own feature is off.
        check(missing.stream().anyMatch(hook -> !hook.yielded() && hook.handler().equals("skein$typeCachePut")
                        && hook.method().equals("markPosition")
                        && hook.features().equals(EnumSet.of(Hooks.Feature.PARALLEL_SAVING))),
                "the overwritten markPosition was not reported as a missing parallel-saving hook: " + missing);
        check(!Hooks.allows(Hooks.Feature.PARALLEL_SAVING) && !SkeinConfig.Live.parallelSaving,
                "parallel saving stayed on without its hook");
        check(SkeinConfig.Live.enabled && SkeinConfig.Live.dimensions && SkeinConfig.Live.entities
                        && SkeinConfig.Live.blockEntities && SkeinConfig.Live.randomTicks,
                "a conflict switched off more than the feature it touched");

        // A redirect of the call Skein wraps: both are in the class.
        check(missing.stream().noneMatch(hook -> hook.mixin().endsWith("ServerCommonPacketListenerMixin")),
                "Skein's packet hook lost to another mod's redirect");
        check(Arrays.stream(ServerCommonPacketListenerImpl.class.getDeclaredMethods())
                        .anyMatch(method -> method.getName().endsWith("skeinTest$conflictSameThread")),
                "the other mod's redirect lost to Skein's hook");

        // A hook inside the tracking loop: Skein yields that loop.
        check(missing.stream().anyMatch(hook -> hook.yielded() && hook.handler().equals("skein$walkOnPool")),
                "tracking did not yield to a hook inside its loop: " + missing);
        check(!SkeinConfig.Live.threadedEntityTracking && !SkeinConfig.Live.trackingHooks,
                "tracking stayed on the pool next to another mod's hook");
        check(missing.stream().noneMatch(hook -> hook.yielded() && !hook.handler().equals("skein$walkOnPool")),
                "something besides tracking yielded: " + missing);

        // Block entities in four separated cells, so their phase runs in parallel.
        ServerLevel level = server.overworld();
        int margin = SkeinConfig.Live.loadedMarginChunks;
        for (int cell = 0; cell < CELLS; cell++) {
            int cx = 4 + 16 * cell;
            for (int dx = -margin; dx <= margin; dx++) for (int dz = -margin; dz <= margin; dz++) {
                level.getChunk(cx + dx, 4 + dz);
                level.setChunkForced(cx + dx, 4 + dz, true);
            }
            for (int i = 0; i < FURNACES_PER_CELL; i++) {
                BlockPos pos = new BlockPos(cx * 16 + (i % 8) * 2, 10 + i / 8, 64 + (i % 3));
                check(level.setBlock(pos, Blocks.FURNACE.defaultBlockState(), 3), "furnace not placed");
            }
        }
        started = true;
    }

    private static void afterTick(ServerTickEvent.Post event) {
        if (!started) return;
        check(TickMetrics.failureCount() == 0, "a parallel tick failed");
        if (TickMetrics.of(TickMetrics.Phase.BLOCK_ENTITIES).parallel() > 0) {
            parallelTicks++;
            // Every ticker went through the other mod's wrapper on its way to
            // Skein's, including the ones that then ran on a worker.
            check(wrapped.get() >= FURNACES_PER_CELL * CELLS,
                    "the other mod's wrapper missed tickers that ran in parallel: " + wrapped.get());
        }
        check(wrappedInPass.get() == 0, "the other mod's wrapper ran inside a parallel pass");
        if (++ticks < 80) return;
        check(parallelTicks >= 20, "block entities ran in parallel on only " + parallelTicks + " ticks");
        String details = "conflict run: " + Hooks.summary() + "; block-entity wrapper stayed outside Skein's on "
                + parallelTicks + " parallel ticks";
        try {
            Files.writeString(Path.of("result.txt"), "PASS: " + details + "\n");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Skein.LOGGER.info("Conflict verification PASS: {}", details);
        event.getServer().halt(false);
    }
}
