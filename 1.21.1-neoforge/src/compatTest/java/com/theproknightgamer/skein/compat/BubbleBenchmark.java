package com.theproknightgamer.skein.compat;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.DimensionTicker;
import com.theproknightgamer.skein.core.TickMetrics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;
import java.time.Duration;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/** Natural snowball physics in one one-block-wide soul-sand bubble column. */
final class BubbleBenchmark {
    private static final int COUNT = 2000;
    private static final int WARMUP = 200;
    private static final int SAMPLES = 400;
    private static final int X = 2080;
    private static final int Z = 32;
    private final List<Snowball> balls = new ArrayList<>();
    private final StringBuilder csv = new StringBuilder(
            "sample,tick_ms,entity_wall_ms,cluster_entities,grid_entities,serial_entities,gc_ms,y,velocity_y\n");
    private final double[] times = new double[SAMPLES];
    private MinecraftServer server;
    private long started;
    private long gcStart;
    private int warmup;
    private int samples;
    private int settling;
    private long clustered;
    private long gridded;
    private String jarHash;
    private Recording recording;

    BubbleBenchmark() {
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, this::start);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, ServerTickEvent.Pre.class, this::before);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ServerTickEvent.Post.class, this::after);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static long gcMillis() {
        long sum = 0;
        for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) sum += Math.max(0, bean.getCollectionTime());
        return sum;
    }

    private void start(ServerStartedEvent event) {
        server = event.getServer();
        check(SkeinConfig.Live.adaptive && SkeinConfig.Live.useAllCpuThreads, "benchmark configuration missing");
        // Verify bytes from the loaded mod, not just the name printed in a log.
        // This catches a baseline run accidentally using current main classes.
        try {
            Path jar = Path.of(System.getProperty("skein.test.jar"));
            jarHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
            try (ZipFile archive = new ZipFile(jar.toFile())) {
                List<String> resources = new ArrayList<>(List.of("com/theproknightgamer/skein/Skein.class",
                        "com/theproknightgamer/skein/mixin/ServerChunkCacheMixin.class", "skein.mixins.json"));
                if (archive.getEntry("com/theproknightgamer/skein/mixin/EntitySectionMixin.class") != null) {
                    resources.add("com/theproknightgamer/skein/mixin/EntitySectionMixin.class");
                }
                for (String resource : resources) {
                    try (var loaded = Skein.class.getClassLoader().getResourceAsStream(resource);
                         var expected = archive.getInputStream(archive.getEntry(resource))) {
                        check(loaded != null && Arrays.equals(loaded.readAllBytes(), expected.readAllBytes()), "wrong benchmark mod loaded: " + resource);
                    }
                }
            }
        } catch (Exception e) { throw new IllegalStateException(e); }
        ServerLevel level = server.overworld();
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, server);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            level.getChunk((X >> 4) + dx, (Z >> 4) + dz);
            level.setChunkForced((X >> 4) + dx, (Z >> 4) + dz, true);
        }
        // Glass retains the water. The top is open so snowballs rise out and
        // fall back in naturally; no teleporting, freezing or replenishment.
        for (int y = 70; y <= 71; y++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx != 0 || dz != 0) level.setBlock(new BlockPos(X + dx, y, Z + dz), Blocks.GLASS.defaultBlockState(), 3);
        }
        level.setBlock(new BlockPos(X, 69, Z), Blocks.SOUL_SAND.defaultBlockState(), 3);
        for (int y = 70; y <= 71; y++) level.setBlock(new BlockPos(X, y, Z),
                Blocks.BUBBLE_COLUMN.defaultBlockState().setValue(BubbleColumnBlock.DRAG_DOWN, false), 3);
        if (Boolean.getBoolean("skein.test.bystander")) {
            // Stands where a player watching the column does: one glass block
            // away, 1.8 blocks from its centre. Pickable and gridded, like them.
            ArmorStand bystander = new ArmorStand(EntityType.ARMOR_STAND, level);
            bystander.setPos(X + .5, 70, Z + .5 - 1.8);
            bystander.setNoGravity(true);
            check(level.addFreshEntity(bystander), "bystander rejected");
        }
        Skein.LOGGER.info("Bubble benchmark: jar {}; {} workers; adaptive {}; bystander {}; warming {} then sampling {} ticks",
                jarHash, Skein.pool().size(), SkeinConfig.Live.adaptive, Boolean.getBoolean("skein.test.bystander"),
                WARMUP, SAMPLES);
    }

    private void before(ServerTickEvent.Pre event) {
        if (balls.isEmpty()) return;
        if (warmup == WARMUP && Boolean.getBoolean("skein.test.profile")) {
            try {
                recording = new Recording(Configuration.getConfiguration("profile"));
                recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1));
                recording.start();
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        gcStart = gcMillis();
        started = System.nanoTime();
    }

    private void after(ServerTickEvent.Post event) {
        if (server == null) return;
        if (balls.isEmpty()) {
            if (++settling < 40) return;
            for (int i = 0; i < COUNT; i++) {
                Snowball ball = new Snowball(server.overworld(), X + .5, 71.25, Z + .5);
                check(server.overworld().addFreshEntity(ball), "snowball rejected");
                balls.add(ball);
            }
            return;
        }
        double elapsed = (System.nanoTime() - started) / 1_000_000.0;
        check(TickMetrics.failureCount() == 0 && DimensionTicker.quarantined().isEmpty(), "snowball tick failure");
        Snowball first = balls.getFirst();
        for (Snowball ball : balls) {
            check(!ball.isRemoved() && ball.tickCount == first.tickCount, "lost or unticked snowball");
            check(ball.getX() == X + .5 && ball.getZ() == Z + .5 && ball.getY() >= 70 && ball.getY() < 80,
                    "snowball left the bubble column");
        }
        if (warmup++ < WARMUP) return;
        TickMetrics.Stats stats = TickMetrics.of(TickMetrics.Phase.ENTITIES);
        clustered += TickMetrics.clusterEntities();
        gridded += stats.parallel();
        times[samples] = elapsed;
        csv.append(String.format(Locale.ROOT, "%d,%.6f,%.6f,%d,%d,%d,%d,%.9f,%.9f%n",
                samples + 1, elapsed, stats.wallMillis(), TickMetrics.clusterEntities(), stats.parallel(), stats.serial(),
                gcMillis() - gcStart, first.getY(), first.getDeltaMovement().y));
        if (++samples < SAMPLES) return;
        if (recording != null) {
            try {
                recording.stop();
                recording.dump(Path.of("snowballs.jfr"));
                recording.close();
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        double mean = Arrays.stream(times).average().orElseThrow();
        Arrays.sort(times);
        String result = String.format(Locale.ROOT,
                "PASS: 2000 snowballs retained in one bubble column; bystander %s; %d warmup + %d measured ticks; "
                        + "mean %.6f ms, median %.6f ms, p95 %.6f ms; clustered %d, gridded %d; %d workers; jar %s%n",
                Boolean.getBoolean("skein.test.bystander"), WARMUP, SAMPLES, mean,
                (times[SAMPLES / 2 - 1] + times[SAMPLES / 2]) / 2,
                times[(int) Math.ceil(SAMPLES * .95) - 1], clustered, gridded, Skein.pool().size(), jarHash);
        // Timings first, so a baseline that fails the scheduler check below
        // still leaves its numbers behind.
        try {
            Files.writeString(Path.of("ticks.csv"), csv);
        } catch (Exception e) { throw new IllegalStateException(e); }
        Skein.LOGGER.info("Bubble benchmark measured: {}", result.strip().substring("PASS: ".length()));
        if (Boolean.getBoolean("skein.test.expectSerialBenchmark")) {
            check(clustered == 0 && gridded == 0, "serial baseline unexpectedly used entity workers");
        } else {
            check(clustered > 0, "cluster scheduler never ran");
        }
        check(first.tickCount == WARMUP + SAMPLES, "snowballs did not tick once per frame");
        try {
            Files.writeString(Path.of("result.txt"), result);
        } catch (Exception e) { throw new IllegalStateException(e); }
        Skein.LOGGER.info(result.strip());
        server = null;
        event.getServer().halt(false);
    }
}
