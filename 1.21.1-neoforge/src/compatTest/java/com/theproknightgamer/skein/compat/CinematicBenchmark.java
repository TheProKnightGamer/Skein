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
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/** Measures the tick body with real detonations, without enabling the serializing profiler. */
final class CinematicBenchmark {
    private static final int X = 2080;
    private static final int Z = 32;
    private static final List<PrimedTnt> fired = new ArrayList<>();
    private static final AtomicInteger detonations = new AtomicInteger();
    private static final StringBuilder csv = new StringBuilder(
            "frame,tick_ms,tnt_at_start,fuse_due,detonations,removed,parallel_entities,entity_wall_ms,gc_ms,tick_threads_cpu_ms,cpu_cores,workers_over_1ms,entity_cells\n");
    private static final ThreadMXBean cpu = ManagementFactory.getThreadMXBean();
    private static long[] threadIds;
    private static long[] cpuBefore;
    private static long serverThreadId;
    private static MinecraftServer server;
    private static long started;
    private static long gcStarted;
    private static long peakExplosionTick;
    private static long peakAnyTick;
    private static int atStart;
    private static int due;
    private static int warmup;
    private static int frame;
    private static int totalDetonations;
    private static int explosionTicks;
    private static int peakExplosions;
    private static boolean active;
    private static Recording recording;
    private static boolean profiled;

    CinematicBenchmark() {
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, CinematicBenchmark::start);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, ServerTickEvent.Pre.class, CinematicBenchmark::before);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ServerTickEvent.Post.class, CinematicBenchmark::after);
        NeoForge.EVENT_BUS.addListener(ExplosionEvent.Detonate.class, event -> {
            if (active && event.getExplosion().getDirectSourceEntity() instanceof PrimedTnt) detonations.incrementAndGet();
        });
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionTime())).sum();
    }

    private static void start(ServerStartedEvent event) {
        server = event.getServer();
        check(ModList.get().isLoaded("mr_osc_dp") && ModList.get().isLoaded("speedeeentity"), "benchmark mods missing");
        check(SkeinConfig.Live.useAllCpuThreads, "benchmark all-CPU config missing");
        check(Skein.pool().size() == Math.max(1, Runtime.getRuntime().availableProcessors() - 1), "incorrect worker count");
        check(server.getFunctions().get(ResourceLocation.parse("orbital:cinematic_nuke")).isPresent(), "cinematic function missing");
        if (Boolean.getBoolean("skein.test.cpu")) {
            check(cpu.isThreadCpuTimeSupported(), "thread CPU accounting unavailable");
            cpu.setThreadCpuTimeEnabled(true);
            serverThreadId = Thread.currentThread().threadId();
            threadIds = java.util.Arrays.stream(cpu.getAllThreadIds()).filter(id -> {
                var info = cpu.getThreadInfo(id);
                return id == serverThreadId || info != null && info.getThreadName().startsWith("Skein Server Worker #");
            }).toArray();
            cpuBefore = new long[threadIds.length];
        }
        ServerLevel level = server.overworld();
        // Maximum normal horizontal motion is four blocks/tick. Preload well
        // beyond its 80-tick drag-limited travel, excluding terrain generation
        // from the intended detonation measurement.
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                level.getChunk((X >> 4) + dx, (Z >> 4) + dz);
                level.setChunkForced((X >> 4) + dx, (Z >> 4) + dz, true);
            }
        }
        for (int y = -59; y < -35; y++) {
            level.setBlock(new BlockPos(X, y, Z), Blocks.STONE.defaultBlockState(), 3);
        }
        Skein.LOGGER.info("Cinematic benchmark: 1089 chunks preloaded; {} workers; adaptive {}; 4 GiB heap; warming up",
                Skein.pool().size(), SkeinConfig.Live.adaptive);
    }

    private static void before(ServerTickEvent.Pre event) {
        if (!active) return;
        atStart = 0;
        due = 0;
        for (PrimedTnt tnt : fired) {
            if (!tnt.isRemoved()) {
                atStart++;
                if (tnt.getFuse() <= 1) due++;
            }
        }
        detonations.set(0);
        if (due > 0 && !profiled && recording == null && Boolean.getBoolean("skein.test.profile")) {
            try {
                recording = new Recording(Configuration.getConfiguration("profile"));
                recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1));
                recording.start();
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        gcStarted = gcMillis();
        if (threadIds != null) for (int i = 0; i < threadIds.length; i++) cpuBefore[i] = cpu.getThreadCpuTime(threadIds[i]);
        started = System.nanoTime();
    }

    private static void after(ServerTickEvent.Post event) {
        if (server == null) return;
        if (!active) {
            if (++warmup < 60) return;
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack()
                    .withLevel(server.overworld()).withPosition(new Vec3(X + .5, -59, Z + .5)).withSuppressedOutput(),
                    "function orbital:cinematic_nuke");
            server.overworld().getAllEntities().forEach(entity -> { if (entity instanceof PrimedTnt tnt) fired.add(tnt); });
            check(fired.size() == 5875, "cinematic nuke did not spawn exactly 5875 TNT");
            active = true;
            Skein.LOGGER.info("Cinematic nuke launched: {} TNT, all fuse 80; command execution excluded from tick measurements", fired.size());
            return;
        }
        long elapsed = System.nanoTime() - started;
        long cpuNanos = threadIds == null ? -1 : 0;
        int busyWorkers = 0;
        if (threadIds != null) for (int i = 0; i < threadIds.length; i++) {
            long used = Math.max(0, cpu.getThreadCpuTime(threadIds[i]) - cpuBefore[i]);
            cpuNanos += used;
            if (threadIds[i] != serverThreadId && used >= 1_000_000) busyWorkers++;
        }
        long gc = gcMillis() - gcStarted;
        if (recording != null && detonations.get() > 0) {
            try {
                recording.stop();
                recording.dump(Path.of("explosion.jfr"));
                recording.close();
                recording = null;
                profiled = true;
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        check(TickMetrics.failureCount() == 0 && DimensionTicker.quarantined().isEmpty(), "cinematic tick failure");
        int exploded = detonations.get();
        int remaining = (int) fired.stream().filter(e -> !e.isRemoved()).count();
        frame++;
        peakAnyTick = Math.max(peakAnyTick, elapsed);
        totalDetonations += exploded;
        if (exploded > 0) {
            explosionTicks++;
            if (elapsed > peakExplosionTick) {
                peakExplosionTick = elapsed;
                peakExplosions = exploded;
            }
        }
        TickMetrics.Stats stats = TickMetrics.of(TickMetrics.Phase.ENTITIES);
        csv.append(String.format(Locale.ROOT, "%d,%.3f,%d,%d,%d,%d,%d,%.3f,%d,%.3f,%.3f,%d,%d%n",
                frame, elapsed / 1_000_000.0, atStart, due, exploded, atStart - remaining,
                stats.parallel(), stats.wallMillis(), gc, cpuNanos < 0 ? -1 : cpuNanos / 1_000_000.0,
                cpuNanos < 0 ? -1 : (double) cpuNanos / elapsed, busyWorkers, stats.cells()));
        if (exploded > 0 && threadIds != null) {
            Skein.LOGGER.info("Cinematic CPU: {} ms on server + Skein workers, {} average CPU cores over this tick, "
                            + "{} workers with >=1 ms CPU, {} entity cells",
                    cpuNanos / 1_000_000.0, (double) cpuNanos / elapsed, busyWorkers, stats.cells());
        }
        if (exploded > 0 || frame % 10 == 0) {
            Skein.LOGGER.info("Cinematic frame {}: {} ms, {} detonations, {} TNT remaining, {} parallel entities, {} ms GC",
                    frame, elapsed / 1_000_000.0, exploded, remaining, stats.parallel(), gc);
        }
        if (remaining > 0) {
            check(frame < 400, "cinematic TNT stopped ticking before detonation");
            return;
        }
        check(totalDetonations == 5875, "TNT was removed without detonating");
        String result = String.format(Locale.ROOT,
                "PASS: one cinematic nuke; 5875 TNT detonated across %d ticks; slowest explosion tick %.3f ms (%d detonations); "
                        + "slowest tick overall %.3f ms; %d workers; adaptive %s; flat world; 4 GiB heap; zero tick failures%n",
                explosionTicks, peakExplosionTick / 1_000_000.0, peakExplosions, peakAnyTick / 1_000_000.0,
                Skein.pool().size(), SkeinConfig.Live.adaptive);
        try {
            Files.writeString(Path.of("ticks.csv"), csv);
            Files.writeString(Path.of("result.txt"), result);
        } catch (Exception e) { throw new IllegalStateException(e); }
        Skein.LOGGER.info(result.strip());
        server = null;
        active = false;
        event.getServer().halt(false);
    }
}
