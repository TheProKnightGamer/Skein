package com.theproknightgamer.skein.compat;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.DimensionTicker;
import com.theproknightgamer.skein.core.TickMetrics;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the real queued size-1 function, including summon and explosion work. */
final class StabBenchmark {
    private static final int X = 2080, Z = 32, TNT = 39122;
    private final AtomicInteger spawned = new AtomicInteger();
    private final AtomicInteger detonations = new AtomicInteger();
    private final Set<Integer> exploded = ConcurrentHashMap.newKeySet();
    private final ThreadMXBean cpu = ManagementFactory.getThreadMXBean();
    private final StringBuilder csv = new StringBuilder("frame,tick_ms,spawned,detonations,total_detonations,remaining_tnt,queued_markers,parallel_entities,entity_wall_ms,entity_cells,gc_ms,tick_threads_cpu_ms,cpu_cores\n");
    private MinecraftServer server;
    private long[] threads, cpuBefore;
    private long started, gcStart, totalNanos, peakNanos, launchNanos;
    private int warmup, frame, previousSpawned, recovery;
    private boolean active;
    private Recording recording;

    StabBenchmark() {
        NeoForge.EVENT_BUS.addListener(this::start);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, ServerTickEvent.Pre.class, this::before);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ServerTickEvent.Post.class, this::after);
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, event -> {
            if (active && event.getEntity() instanceof PrimedTnt tnt && tnt.getTags().contains("osc_sn_stab")) spawned.incrementAndGet();
        });
        NeoForge.EVENT_BUS.addListener(ExplosionEvent.Detonate.class, event -> {
            if (active && event.getExplosion().getDirectSourceEntity() instanceof PrimedTnt tnt && tnt.getTags().contains("osc_sn_stab")) {
                check(exploded.add(tnt.getId()), "TNT detonated twice");
                detonations.incrementAndGet();
            }
        });
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionTime())).sum();
    }
    private void start(ServerStartedEvent event) {
        server = event.getServer();
        check(server.getFunctions().get(ResourceLocation.parse("orbital:stab_nuke_1")).isPresent(), "size-1 stab function missing");
        check(SkeinConfig.Live.useAllCpuThreads && SkeinConfig.Live.entities, "parallel benchmark config missing");
        var level = server.overworld();
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        for (int dx = -16; dx <= 16; dx++) for (int dz = -16; dz <= 16; dz++) {
            level.getChunk((X >> 4) + dx, (Z >> 4) + dz);
            level.setChunkForced((X >> 4) + dx, (Z >> 4) + dz, true);
        }
        check(level.getBlockState(new BlockPos(X, 63, Z)).is(Blocks.GRASS_BLOCK)
                && level.getBlockState(new BlockPos(X, 64, Z)).isAir()
                && level.getBlockState(new BlockPos(X, 24, Z)).is(Blocks.STONE), "expected thick flat world, surface Y=64");
        cpu.setThreadCpuTimeEnabled(true);
        long serverId = Thread.currentThread().threadId();
        threads = Arrays.stream(cpu.getAllThreadIds()).filter(id -> {
            var info = cpu.getThreadInfo(id);
            return id == serverId || info != null && info.getThreadName().startsWith("Skein Server Worker #");
        }).toArray();
        cpuBefore = new long[threads.length];
        Skein.LOGGER.info("Stab benchmark ready: thick stone surface Y=64; 1089 chunks; {} workers; adaptive {}; warming up", Skein.pool().size(), SkeinConfig.Live.adaptive);
    }
    private void before(ServerTickEvent.Pre event) {
        if (!active) return;
        detonations.set(0);
        gcStart = gcMillis();
        for (int i = 0; i < threads.length; i++) cpuBefore[i] = cpu.getThreadCpuTime(threads[i]);
        started = System.nanoTime();
    }
    private void after(ServerTickEvent.Post event) {
        if (server == null) return;
        if (!active) {
            if (++warmup < 60) return;
            if (Boolean.getBoolean("skein.test.profile")) try {
                recording = new Recording(Configuration.getConfiguration("profile"));
                recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1));
                recording.start();
            } catch (Exception e) { throw new IllegalStateException(e); }
            active = true;
            long start = System.nanoTime();
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(server.overworld())
                    .withPosition(new Vec3(X + .5, 64, Z + .5)).withSuppressedOutput(), "function orbital:stab_nuke_1");
            launchNanos = System.nanoTime() - start;
            check(countTag("osc_sn_pending") == 631 && spawned.get() == 0, "expected 631 queued markers before processing");
            Skein.LOGGER.info("Size-1 stab launched: 631 markers, 39122 expected TNT; queue setup {} ms", launchNanos / 1e6);
            return;
        }
        long elapsed = System.nanoTime() - started;
        long used = 0;
        for (int i = 0; i < threads.length; i++) used += Math.max(0, cpu.getThreadCpuTime(threads[i]) - cpuBefore[i]);
        long gc = gcMillis() - gcStart;
        check(TickMetrics.failureCount() == 0 && DimensionTicker.quarantined().isEmpty(), "stab tick failure");
        frame++;
        long remaining = countTag("osc_sn_stab"), queued = countTag("osc_sn_pending");
        var stats = TickMetrics.of(TickMetrics.Phase.ENTITIES);
        totalNanos += elapsed;
        peakNanos = Math.max(peakNanos, elapsed);
        csv.append(String.format(Locale.ROOT, "%d,%.3f,%d,%d,%d,%d,%d,%d,%.3f,%d,%d,%.3f,%.3f%n", frame, elapsed / 1e6,
                spawned.get() - previousSpawned, detonations.get(), exploded.size(), remaining, queued,
                stats.parallel(), stats.wallMillis(), stats.cells(), gc, used / 1e6, (double) used / elapsed));
        previousSpawned = spawned.get();
        try { Files.writeString(Path.of("ticks.csv"), csv); } catch (Exception e) { throw new IllegalStateException(e); }
        if (detonations.get() > 0 || frame % 20 == 0) Skein.LOGGER.info("Stab frame {}: {} ms; {} detonations; {} total; {} TNT remaining; {} markers; {} CPU cores",
                frame, elapsed / 1e6, detonations.get(), exploded.size(), remaining, queued, (double) used / elapsed);
        check(frame < 200, "stab queue or TNT stalled");
        if (remaining != 0 || queued != 0) return;
        check(spawned.get() == TNT && exploded.size() == TNT, "TNT spawn/detonation mismatch: " + spawned + "/" + exploded.size());
        if (detonations.get() != 0) return;
        if (++recovery < 20) return;
        String result = String.format(Locale.ROOT, "PASS: size-1 stab; all %d TNT spawned and detonated once; %d ticks including 20 recovery ticks; peak tick %.3f ms; measured tick total %.3f ms; queue setup %.3f ms; zero tick failures%n", TNT, frame, peakNanos / 1e6, totalNanos / 1e6, launchNanos / 1e6);
        try {
            if (recording != null) { recording.stop(); recording.dump(Path.of("stab.jfr")); recording.close(); }
            Files.writeString(Path.of("result.txt"), result);
        } catch (Exception e) { throw new IllegalStateException(e); }
        Skein.LOGGER.info(result.strip());
        active = false;
        server.halt(false);
        server = null;
    }
    private long countTag(String tag) {
        long count = 0;
        for (var entity : server.overworld().getAllEntities()) if (!entity.isRemoved() && entity.getTags().contains(tag)) count++;
        return count;
    }
}
