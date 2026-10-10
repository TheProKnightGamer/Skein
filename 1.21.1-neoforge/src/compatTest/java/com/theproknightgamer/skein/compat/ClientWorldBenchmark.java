package com.theproknightgamer.skein.compat;

import com.mojang.authlib.GameProfile;
import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.DimensionTicker;
import com.theproknightgamer.skein.core.TickMetrics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.CommandBlock;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/**
 * The client session that reported 50 ms ticks, replayed on a dedicated server:
 * a copy of that world, and its owner joining over an in-memory connection with
 * the client's render distance, standing where they stood. Everything the world
 * does -- command blocks, the snowball column, chunk ticking, chunk sending,
 * entity tracking -- runs as it did for them.
 */
final class ClientWorldBenchmark {
    private static final UUID ID = UUID.fromString("6b7120ec-40b6-42e8-b0a6-0739e2f94adf");
    private static final String NAME = "TheProKnightGamer";
    private static final int VIEW_DISTANCE = 32;
    private static final int SETTLE_MIN = 600;
    private static final int SETTLE_MAX = 4000;
    private static final int STABLE = 200;
    private static final int WARMUP = 200;
    private static final int SAMPLES = Integer.getInteger("skein.test.samples", 600);
    /** Optional: a repeating, always-active command block placed once the view has settled. */
    private static final String COMMAND = System.getProperty("skein.test.clientWorldCommand", "");
    private static final BlockPos COMMAND_AT = new BlockPos(786, 70, -327);

    private final StringBuilder csv = new StringBuilder("sample,tick_ms,entity_wall_ms,cluster_entities,gc_ms\n");
    private final double[] times = new double[SAMPLES];
    private MinecraftServer server;
    private ServerPlayer player;
    private BenchConnection connection;
    private long started;
    private long gcStart;
    private int settling;
    private int stableFor;
    private int lastLoaded = -1;
    private long lastBatches = -1;
    private int warmup;
    private int samples;
    private long clustered;
    private double entityWall;
    private Recording recording;

    ClientWorldBenchmark() {
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, this::start);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, ServerTickEvent.Pre.class, this::before);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, false, ServerTickEvent.Post.class, this::client);
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
        ServerLevel level = server.overworld();
        // The client crashed before the column's chunk was written, though the
        // 1,880 snowballs in it were saved. Rebuild it under them -- they bob
        // between y 74.7 and 75.5 at (774.5, -325.5) -- before anything loads
        // them, so they settle back into the column instead of falling out.
        BlockPos column = new BlockPos(774, 73, -326);
        if (!level.getBlockState(column).is(Blocks.BUBBLE_COLUMN)) {
            for (int y = 72; y <= 74; y++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) level.setBlock(column.offset(dx, y - 73, dz), Blocks.GLASS.defaultBlockState(), 3);
            }
            level.setBlock(column.below(), Blocks.SOUL_SAND.defaultBlockState(), 3);
            for (int y = 0; y <= 1; y++) level.setBlock(column.above(y),
                    Blocks.BUBBLE_COLUMN.defaultBlockState().setValue(BubbleColumnBlock.DRAG_DOWN, false), 3);
        }
        GameProfile profile = new GameProfile(ID, NAME);
        ClientInformation info = new ClientInformation("en_us", VIEW_DISTANCE, ChatVisiblity.FULL, true, 0x7F,
                HumanoidArm.RIGHT, false, true);
        player = new ServerPlayer(server, level, profile, info);
        connection = new BenchConnection();
        server.getPlayerList().placeNewPlayer(connection, player, new CommonListenerCookie(profile, 0, info, false));
        check(player.distanceToSqr(774.45, 77.51, -327.3) < 4, "player did not load at the saved position: " + player.position());
        Skein.LOGGER.info("Client-world benchmark: {} at {}; view {}, simulation {}; {} workers; adaptive {}",
                NAME, player.position(), server.getPlayerList().getViewDistance(),
                server.getPlayerList().getSimulationDistance(), Skein.pool() == null ? 0 : Skein.pool().size(),
                SkeinConfig.Live.adaptive);
    }

    private void before(ServerTickEvent.Pre event) {
        if (player == null) return;
        if (settling < 0 && warmup == WARMUP && samples == 0 && recording == null && Boolean.getBoolean("skein.test.profile")) {
            try {
                recording = new Recording(Configuration.getConfiguration("profile"));
                recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(2));
                recording.start();
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        gcStart = gcMillis();
        started = System.nanoTime();
    }

    /** What the connection listener tick and the client would have done this tick. */
    private void client(ServerTickEvent.Post event) {
        if (player == null) return;
        connection.answer(player);
        player.connection.tick();
    }

    private void after(ServerTickEvent.Post event) {
        if (player == null) return;
        double elapsed = (System.nanoTime() - started) / 1_000_000.0;
        check(DimensionTicker.quarantined().isEmpty(), "a dimension was quarantined");
        if (settling >= 0) {
            // Wait for the view to finish loading and sending, as a player would.
            int loaded = server.overworld().getChunkSource().getLoadedChunksCount();
            boolean still = loaded == lastLoaded && connection.chunkBatches == lastBatches;
            stableFor = still ? stableFor + 1 : 0;
            lastLoaded = loaded;
            lastBatches = connection.chunkBatches;
            if (++settling % 200 == 0) {
                Skein.LOGGER.info("Client-world benchmark settling: tick {}, {} chunks loaded, {} batches sent, last tick {} ms",
                        settling, loaded, connection.chunkBatches, String.format(Locale.ROOT, "%.2f", elapsed));
            }
            if (settling >= SETTLE_MAX || settling >= SETTLE_MIN && stableFor >= STABLE) {
                Skein.LOGGER.info("Client-world benchmark settled after {} ticks: {} chunks, {} sent to the client, {} entities, {} snowballs",
                        settling, loaded, connection.chunksSent, count(null), count(EntityType.SNOWBALL));
                settling = -1;
                if (!COMMAND.isEmpty()) placeCommandBlock();
            }
            return;
        }
        if (warmup++ < WARMUP) {
            PhaseTimes.enabled = warmup == WARMUP;
            SectionTimes.enabled = warmup == WARMUP;
            return;
        }
        TickMetrics.Stats stats = TickMetrics.of(TickMetrics.Phase.ENTITIES);
        clustered += TickMetrics.clusterEntities();
        entityWall += stats.wallMillis();
        times[samples] = elapsed;
        csv.append(String.format(Locale.ROOT, "%d,%.6f,%.6f,%d,%d%n", samples + 1, elapsed, stats.wallMillis(),
                TickMetrics.clusterEntities(), gcMillis() - gcStart));
        if (++samples < SAMPLES) return;
        if (recording != null) {
            try {
                recording.stop();
                recording.dump(Path.of("client-world.jfr"));
                recording.close();
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        double mean = Arrays.stream(times).average().orElseThrow();
        Arrays.sort(times);
        String result = String.format(Locale.ROOT,
                "PASS: client world; %d measured ticks after %d warmup; mean %.3f ms, median %.3f ms, p95 %.3f ms, "
                        + "max %.3f ms; %d chunks loaded, %d sent, %d entities, %d snowballs, %d tnt, %d items; clustered %d; %d workers%n",
                SAMPLES, WARMUP, mean, (times[SAMPLES / 2 - 1] + times[SAMPLES / 2]) / 2,
                times[(int) Math.ceil(SAMPLES * .95) - 1], times[SAMPLES - 1],
                server.overworld().getChunkSource().getLoadedChunksCount(), connection.chunksSent, count(null),
                count(EntityType.SNOWBALL), count(EntityType.TNT), count(EntityType.ITEM),
                clustered, Skein.pool() == null ? 0 : Skein.pool().size());
        try {
            Files.writeString(Path.of("ticks.csv"), csv);
            Files.writeString(Path.of("result.txt"), result);
        } catch (Exception e) { throw new IllegalStateException(e); }
        Skein.LOGGER.info(result.strip());
        PhaseTimes.enabled = false;
        SectionTimes.enabled = false;
        String split = "Client-world split per tick: entity workers wall " + String.format(Locale.ROOT, "%.3f ms; ", entityWall / SAMPLES)
                + PhaseTimes.report(SAMPLES);
        Skein.LOGGER.info(split);
        try {
            Files.writeString(Path.of("split.txt"), split + "\n" + SectionTimes.report(SAMPLES) + "\n");
        } catch (Exception e) { throw new IllegalStateException(e); }
        player = null;
        event.getServer().halt(false);
    }

    private void placeCommandBlock() {
        ServerLevel level = server.overworld();
        level.setBlock(COMMAND_AT, Blocks.REPEATING_COMMAND_BLOCK.defaultBlockState()
                .setValue(CommandBlock.FACING, Direction.UP), 3);
        check(level.getBlockEntity(COMMAND_AT) instanceof CommandBlockEntity, "command block did not place");
        CommandBlockEntity block = (CommandBlockEntity) level.getBlockEntity(COMMAND_AT);
        block.getCommandBlock().setCommand(COMMAND);
        block.setAutomatic(true);
        block.setChanged();
        Skein.LOGGER.info("Client-world benchmark: repeating always-active command block at {} running {}", COMMAND_AT, COMMAND);
    }

    private int count(EntityType<?> type) {
        int n = 0;
        for (Entity entity : server.overworld().getAllEntities()) {
            if (type == null || entity.getType() == type) n++;
        }
        return n;
    }
}
