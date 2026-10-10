package com.theproknightgamer.skein.compat;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.DimensionTicker;
import com.theproknightgamer.skein.core.TickMetrics;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Real datapack strikes, after the collision/worker stress checks finish. */
final class OrbitalVerification {
    private static final int[] SITES = { 2080, 2592 };
    private static final List<PrimedTnt> fired = new ArrayList<>();
    private static MinecraftServer server;
    private static int frame;
    private static int phase;
    private static long parallelFrames;
    private static long peakParallel;
    private static long longestTick;
    private static long tickStarted;
    private static int totalSpawned;
    private static boolean ready;

    OrbitalVerification() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Pre.class, event -> {
            if (server != null) tickStarted = System.nanoTime();
        });
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, OrbitalVerification::tick);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void start(MinecraftServer minecraftServer) {
        check(ModList.get().isLoaded("mr_osc_dp"), "requested Orbital jar did not load");
        server = minecraftServer;
        ServerLevel level = server.overworld();
        // Exercise the user's 'if it wants to' behavior after deterministic
        // stress tests: the normal adaptive governor stays enabled for strikes.
        SkeinConfig.Live.adaptive = true;
        SkeinConfig.Live.clusterEntities = true;
        for (int x : SITES) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    level.getChunk((x >> 4) + dx, 2 + dz);
                    level.setChunkForced((x >> 4) + dx, 2 + dz, true);
                }
            }
            // A visible target near the top of the flat world's bedrock floor.
            for (int y = -59; y < -35; y++) {
                level.setBlock(new BlockPos(x, y, 32), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        ready = true;
        Skein.LOGGER.info("Orbital jar loaded; warming forced chunks before simultaneous cannon strikes");
    }

    private static void fire(String function, int expected) {
        check(server.getFunctions().get(ResourceLocation.parse(function)).isPresent(), "missing cannon function " + function);
        ServerLevel level = server.overworld();
        for (int x : SITES) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack()
                    .withLevel(level).withPosition(new Vec3(x + .5, -59, 32.5)).withSuppressedOutput(),
                    "function " + function);
        }
        fired.clear();
        level.getAllEntities().forEach(entity -> { if (entity instanceof PrimedTnt tnt) fired.add(tnt); });
        check(fired.size() == expected, "cannon spawned " + fired.size() + " TNT; expected " + expected);
        totalSpawned += fired.size();
        Skein.LOGGER.info("Orbital strike {}: {} live TNT, {} Skein workers, adaptive {}",
                function, fired.size(), Skein.pool().size(), SkeinConfig.Live.adaptive);
        frame = 0;
    }

    private static void tick(ServerTickEvent.Post event) {
        if (!ready) return;
        check(TickMetrics.failureCount() == 0 && DimensionTicker.quarantined().isEmpty(), "cannon caused a parallel tick failure");
        long parallel = TickMetrics.of(TickMetrics.Phase.ENTITIES).parallel();
        if (phase > 0) {
            if (tickStarted != 0) longestTick = Math.max(longestTick, System.nanoTime() - tickStarted);
            if (parallel > 0) parallelFrames++;
            peakParallel = Math.max(peakParallel, parallel);
        }
        if (phase == 0) {
            if (++frame < 40) return;
            fire("orbital:stab_power_3", 404);
            phase = 1;
            return;
        }
        if (++frame % 20 == 0) {
            Skein.LOGGER.info("Orbital phase {}, frame {}: {} TNT remain, {} parallel entities",
                    phase, frame, fired.stream().filter(e -> !e.isRemoved()).count(), parallel);
        }
        if (fired.stream().anyMatch(entity -> !entity.isRemoved())) {
            check(frame < 400, "cannon TNT did not finish within 400 ticks");
            return;
        }
        if (phase == 1) {
            check(SITES.length == 2, "fixture count changed");
            check(server.overworld().getBlockState(new BlockPos(SITES[0], -50, 32)).isAir(), "stab failed to damage its target");
            fire("orbital:nuke_power_1", 1262);
            phase = 2;
            return;
        }
        check(parallelFrames > 0 && peakParallel >= 100, "cannon did not exercise parallel ticking");
        String result = "PASS: all-CPU mode, " + Runtime.getRuntime().availableProcessors() + " logical processors, "
                + Skein.pool().size() + " workers; SpeeDee collision checks and 512-entity stress; Orbital jar loaded; "
                + "two stab_power_3 and two nuke_power_1 strikes, " + totalSpawned + " TNT spawned and removed; "
                + parallelFrames + " parallel frames; peak " + peakParallel + " parallel entities; longest tick "
                + longestTick / 1_000_000 + " ms; zero tick failures\n";
        try { Files.writeString(Path.of("result.txt"), result); }
        catch (Exception e) { throw new IllegalStateException(e); }
        Skein.LOGGER.info(result.strip());
        ready = false;
        server.halt(false);
    }
}
