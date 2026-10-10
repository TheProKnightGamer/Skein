package com.theproknightgamer.skein.compat;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.PistonPushes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A piston pushing a 600-TNT stack ({@code -PpistonTest}): once with the vanilla loop and once
 * spread over the pool ({@code entities.parallelPistonPushes}), at two identical setups. Every TNT
 * must end in the same place, relative to its setup, both ways, and the second push must actually
 * have gone to the pool.
 */
public final class PistonVerification {
    private static final int Y = 150;
    private static final int STACK = 600;
    private static final BlockPos A = new BlockPos(6000, Y, 6000);
    private static final BlockPos B = new BlockPos(6000, Y, 6064);

    private static final List<PrimedTnt> stackA = new ArrayList<>();
    private static final List<PrimedTnt> stackB = new ArrayList<>();
    private static boolean running;
    private static int ticks;
    private static Map<String, Integer> endA;
    private static long batchesBefore;
    private static boolean savedFlag;

    PistonVerification() {
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, PistonVerification::start);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, PistonVerification::afterTick);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static void build(ServerLevel level, BlockPos at, List<PrimedTnt> stack) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            level.setChunkForced((at.getX() >> 4) + dx, (at.getZ() >> 4) + dz, true);
        }
        level.setBlock(at, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST), 3);
        level.setBlock(at.east(), Blocks.STONE.defaultBlockState(), 3);
        for (int i = 0; i < STACK; i++) {
            PrimedTnt tnt = new PrimedTnt(EntityType.TNT, level);
            tnt.setPos(at.getX() + 2.5, at.getY(), at.getZ() + .5);
            tnt.setNoGravity(true);
            tnt.setFuse(30000);
            check(level.addFreshEntity(tnt), "TNT rejected");
            stack.add(tnt);
        }
    }

    private static Map<String, Integer> positions(List<PrimedTnt> stack, BlockPos origin) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Entity tnt : stack) {
            String key = String.format("%.6f,%.6f,%.6f", tnt.getX() - origin.getX(), tnt.getY() - origin.getY(),
                    tnt.getZ() - origin.getZ());
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    private static void start(ServerStartedEvent event) {
        ServerLevel level = event.getServer().overworld();
        build(level, A, stackA);
        build(level, B, stackB);
        savedFlag = SkeinConfig.Live.parallelPistonPushes;
        running = true;
        Skein.LOGGER.info("Piston run: two {}-TNT stacks, one pushed by the vanilla loop and one spread over the pool", STACK);
    }

    private static void afterTick(ServerTickEvent.Post event) {
        if (!running) return;
        ServerLevel level = event.getServer().overworld();
        ticks++;
        if (ticks == 5) {
            SkeinConfig.Live.parallelPistonPushes = false;
            level.setBlock(A.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        } else if (ticks == 15) {
            endA = positions(stackA, A);
            SkeinConfig.Live.parallelPistonPushes = true;
            batchesBefore = PistonPushes.batches();
            level.setBlock(B.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        } else if (ticks == 25) {
            Map<String, Integer> endB = positions(stackB, B);
            long batches = PistonPushes.batches() - batchesBefore;
            SkeinConfig.Live.parallelPistonPushes = savedFlag;
            running = false;
            check(endA.size() >= 1 && !endA.containsKey(String.format("%.6f,%.6f,%.6f", 2.5, 0.0, 0.5)),
                    "the vanilla push did not move the stack: " + endA);
            check(endA.equals(endB), "the pooled push left the stack elsewhere: vanilla " + endA + ", pooled " + endB);
            check(batches > 0, "the push never went to the pool");
            finish(event.getServer(), STACK + " TNT pushed by a piston ended in the same places with the push spread over "
                    + "the pool (" + batches + " pooled pushes) as with the vanilla loop: " + endA);
        }
    }

    private static void finish(MinecraftServer server, String details) {
        try {
            Files.writeString(Path.of("result.txt"), "PASS: " + details + "\n");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Skein.LOGGER.info("Piston verification PASS: {}", details);
        server.halt(false);
    }
}
