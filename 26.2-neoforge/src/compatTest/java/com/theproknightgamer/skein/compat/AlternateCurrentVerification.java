package com.theproknightgamer.skein.compat;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.core.AlternateCurrentCompat;
import com.theproknightgamer.skein.core.TickMetrics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Alternate Current beside parallel block entities ({@code -PalternateCurrentTest},
 * with the mod's jar in {@code -PextraTestModsDir}).
 *
 * <p>A hundred daylight detectors, each driving a twelve-block line of wire into
 * a lamp, spread over many cells. A detector re-reads the sky every twenty
 * ticks from its block entity, so when the time jumps from noon to midnight and
 * back, every detector changes its output in the same block-entity pass, on
 * several threads at once, and every one of those changes walks its wire
 * through Alternate Current's handler. Each line must then hold exactly the
 * power vanilla gives it -- fifteen next to the detector, one less per block --
 * and each lamp must follow, through several flips, with no tick failing.
 */
public final class AlternateCurrentVerification {
    private static final int X = 4096;
    private static final int Z = 4096;
    /**
     * On a stone floor in the air: the superflat plains world grows villages,
     * whose paths pop wires off, whose wells wash them away and whose houses
     * shade detectors.
     */
    private static final int Y = 101;
    private static final int SIDE = 10;
    private static final int SPACING = 34;
    private static final int LENGTH = 12;
    private static final int FLIPS = 6;
    private static final int SETTLE = 45;

    private static boolean running;
    private static int ticks;
    private static int flips;
    private static long failuresAtStart;
    private static long parallelAtStart;

    AlternateCurrentVerification() {
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, AlternateCurrentVerification::start);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, AlternateCurrentVerification::afterTick);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static BlockPos detector(int i) {
        return new BlockPos(X - SIDE * SPACING / 2 + (i % SIDE) * SPACING, Y,
                Z - SIDE * SPACING / 2 + (i / SIDE) * SPACING);
    }

    private static void start(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        if (!ModList.get().isLoaded("alternate_current")) {
            finish(server, "optional mod absent");
            return;
        }
        ServerLevel level = server.overworld();
        for (int cx = -16; cx <= 16; cx++) for (int cz = -16; cz <= 16; cz++) {
            level.setChunkForced((X >> 4) + cx, (Z >> 4) + cz, true);
        }
        level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
        level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
        command(server, "weather clear 1000000");
        command(server, "time set 6000");
        for (int i = 0; i < SIDE * SIDE; i++) {
            BlockPos at = detector(i);
            for (int k = 0; k <= LENGTH + 1; k++) {
                level.setBlock(at.offset(k, -1, 0), Blocks.STONE.defaultBlockState(), 2 | 16);
            }
            level.setBlock(at, Blocks.DAYLIGHT_DETECTOR.defaultBlockState(), 3);
            for (int k = 1; k <= LENGTH; k++) {
                check(level.setBlock(at.east(k), Blocks.REDSTONE_WIRE.defaultBlockState(), 3),
                        "could not place wire at " + at.east(k) + ": " + level.getBlockState(at.east(k))
                                + " on " + level.getBlockState(at.east(k).below()));
            }
            level.setBlock(at.east(LENGTH + 1), Blocks.REDSTONE_LAMP.defaultBlockState(), 3);
        }
        for (int i = 0; i < SIDE * SIDE; i++) {
            for (int k = 1; k <= LENGTH; k++) {
                BlockPos wire = detector(i).east(k);
                check(level.getBlockState(wire).is(Blocks.REDSTONE_WIRE), "wire gone right after placing at " + wire
                        + ": " + level.getBlockState(wire) + " on " + level.getBlockState(wire.below()));
            }
        }
        // Enough tickers that the block-entity phase splits, in rows halfway
        // between the circuits' rows.
        for (int row = 0; row < SIDE; row++) {
            for (int i = 0; i < 26; i++) {
                level.setBlock(new BlockPos(X - SIDE * SPACING / 2 + i * 13, Y,
                        Z - SIDE * SPACING / 2 + SPACING / 2 + row * SPACING), Blocks.FURNACE.defaultBlockState(), 3);
            }
        }
        failuresAtStart = TickMetrics.failureCount();
        parallelAtStart = TickMetrics.of(TickMetrics.Phase.BLOCK_ENTITIES).parallel();
        running = true;
        Skein.LOGGER.info("Alternate Current run: {} daylight detectors driving {}-block wires into lamps, "
                + "{} day/night flips", SIDE * SIDE, LENGTH, FLIPS);
    }

    private static void afterTick(ServerTickEvent.Post event) {
        if (!running) return;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        ticks++;
        if (ticks % SETTLE != 0) return;
        boolean day = flips % 2 == 0;
        verify(level, day);
        if (flips == 0) {
            verifyHandlers(level);
        }
        flips++;
        if (flips >= FLIPS + 1) {
            long parallel = TickMetrics.of(TickMetrics.Phase.BLOCK_ENTITIES).parallel() - parallelAtStart;
            check(parallel > 0, "the block-entity phase never ran in parallel");
            check(TickMetrics.failureCount() == failuresAtStart,
                    "ticks failed: " + failuresAtStart + " -> " + TickMetrics.failureCount());
            running = false;
            finish(server, SIDE * SIDE + " circuits matched vanilla's wire power and lamp state through " + FLIPS
                    + " day/night flips, each changed by every detector in one parallel block-entity pass ("
                    + parallel + " block entities ticked in parallel); one handler per worker and the server "
                    + "thread, all sharing the level's config");
            return;
        }
        command(level.getServer(), "time set " + (flips % 2 == 0 ? 6000 : 18000));
    }

    /** 26.2 keeps time and weather in its world clock; the commands are the stable way in. */
    private static void command(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    }

    private static void verify(ServerLevel level, boolean day) {
        for (int i = 0; i < SIDE * SIDE; i++) {
            BlockPos at = detector(i);
            for (int k = 1; k <= LENGTH; k++) {
                BlockState wire = level.getBlockState(at.east(k));
                check(wire.is(Blocks.REDSTONE_WIRE), "wire missing at " + at.east(k) + ": " + wire
                        + " on " + level.getBlockState(at.east(k).below()) + " (flip " + flips + ")");
                int expected = day ? 16 - k : 0;
                check(wire.getValue(RedStoneWireBlock.POWER) == expected, "circuit " + i + " wire " + k + " has power "
                        + wire.getValue(RedStoneWireBlock.POWER) + ", expected " + expected + " (flip " + flips + ")");
            }
            BlockState lamp = level.getBlockState(at.east(LENGTH + 1));
            check(lamp.getValue(RedstoneLampBlock.LIT) == day, "circuit " + i + " lamp lit="
                    + lamp.getValue(RedstoneLampBlock.LIT) + ", expected " + day + " (flip " + flips + ")");
        }
    }

    private static void verifyHandlers(ServerLevel level) {
        check(level instanceof AlternateCurrentCompat.LevelAccess, "the Alternate Current hook did not apply");
        AlternateCurrentCompat.Handlers handlers = ((AlternateCurrentCompat.LevelAccess) level).skein$wireHandlers();
        check(handlers != null && handlers.pool() == Skein.pool(), "per-thread wire handlers were not built");
        Map<Thread, Object> byThread = handlers.byThread();
        check(byThread.size() == Skein.pool().size() + 1,
                "expected a handler per worker and the server thread, got " + byThread.size());
        try {
            Object own = level.getClass().getMethod("alternate_current$getWireHandler").invoke(level);
            Object config = own.getClass().getMethod("getConfig").invoke(own);
            Set<Object> seen = new HashSet<>();
            for (Object handler : byThread.values()) {
                check(handler != own && seen.add(handler), "a per-thread handler is shared");
                check(handler.getClass().getMethod("getConfig").invoke(handler) == config,
                        "a per-thread handler has its own config");
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not inspect the wire handlers", e);
        }
    }

    private static void finish(MinecraftServer server, String details) {
        try {
            Files.writeString(Path.of("result.txt"), "PASS: " + details + "\n");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Skein.LOGGER.info("Alternate Current verification PASS: {}", details);
        server.halt(false);
    }
}
