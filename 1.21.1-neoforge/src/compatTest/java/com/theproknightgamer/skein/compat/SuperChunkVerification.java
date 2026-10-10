package com.theproknightgamer.skein.compat;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.DeferredBuffer;
import com.theproknightgamer.skein.core.LithiumCompat;
import com.theproknightgamer.skein.core.SkeinRefusal;
import com.theproknightgamer.skein.core.SuperChunkBridge;
import com.theproknightgamer.skein.core.TickContext;
import com.theproknightgamer.skein.core.TickMetrics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/** Real ticking blocks in all dimensions, plus the serialized cold-chunk path. */
final class SuperChunkVerification {
    private record Position(ServerLevel level, BlockPos pos) {}
    private static final List<Position> hoppers = new ArrayList<>();
    private static final List<Position> randomLeaves = new ArrayList<>();
    private static final List<Position> scheduledLeaves = new ArrayList<>();
    private static final EnumMap<TickMetrics.Phase, Long> parallel = new EnumMap<>(TickMetrics.Phase.class);
    private static int samples;
    private static boolean scheduled;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void start(MinecraftServer server) {
        check(LithiumCompat.installed() && LithiumCompat.stillEnabled().isEmpty(), "unsafe Lithium startup options active");
        check(SkeinConfig.Live.entities && SkeinConfig.Live.randomTicks && SkeinConfig.Live.blockEntities
                && SkeinConfig.Live.scheduledTicks && !SkeinConfig.Live.parallelSaving, "SuperChunk phase policy incorrect");
        for (var phase : TickMetrics.Phase.values()) parallel.put(phase, 0L);
        for (ServerLevel level : server.getAllLevels()) {
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
            level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(256, server);
            for (int cell = 0; cell < 4; cell++) {
                int origin = 2048 + cell * 256;
                // Eight interior chunks in each of four independent cells.
                for (int dx = 2; dx <= 5; dx++) for (int dz = 2; dz <= 3; dz++) {
                    ChunkPos cp = new ChunkPos((origin >> 4) + dx, dz);
                    level.getChunk(cp.x, cp.z);
                    level.getChunkSource().addRegionTicket(TicketType.FORCED, cp, 2, cp, true);
                    BlockPos leaf = new BlockPos(cp.getMinBlockX() + 2, 220, cp.getMinBlockZ() + 2);
                    level.setBlock(leaf, Blocks.OAK_LEAVES.defaultBlockState()
                            .setValue(LeavesBlock.DISTANCE, 7).setValue(LeavesBlock.PERSISTENT, false), 3);
                    randomLeaves.add(new Position(level, leaf));
                }
                for (int i = 0; i < 24; i++) {
                    BlockPos pos = new BlockPos(origin + 40 + (i % 6) * 4, 180, 40 + (i / 6) * 4);
                    level.setBlock(pos, Blocks.HOPPER.defaultBlockState(), 3);
                    level.setBlock(pos.above(), Blocks.CHEST.defaultBlockState(), 3);
                    ((Container) level.getBlockEntity(pos.above())).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
                    hoppers.add(new Position(level, pos));
                }
                for (int i = 0; i < 16; i++) {
                    BlockPos pos = new BlockPos(origin + 40 + i % 4 * 4, 200, 40 + i / 4 * 4);
                    level.setBlock(pos, Blocks.BIRCH_LEAVES.defaultBlockState()
                            .setValue(LeavesBlock.DISTANCE, 1).setValue(LeavesBlock.PERSISTENT, true), 2);
                    scheduledLeaves.add(new Position(level, pos));
                }
            }
        }
        coldChunkReads(server.overworld());
        Skein.LOGGER.info("SuperChunk fixtures ready: {} hopper/chest pairs, {} random leaves, {} scheduled leaves across 3 dimensions",
                hoppers.size(), randomLeaves.size(), scheduledLeaves.size());
    }

    private static void coldChunkReads(ServerLevel level) {
        DeferredBuffer[] buffers = new DeferredBuffer[4];
        for (int i = 0; i < buffers.length; i++) buffers[i] = new DeferredBuffer();
        Skein.pool().invokeAll(4, i -> {
            var previous = TickContext.enter(level, true, buffers[i]);
            try {
                check(!SuperChunkBridge.ownsChunkTasks(level), "cell claimed chunk executor without its lock");
                // A parallel tick never loads a chunk (ServerChunkCacheMixin): an
                // optional read gets vanilla's answer for an absent chunk, and a
                // required one is refused, ending only the asker's tick.
                int x = 3000 + i * 4;
                check(level.getChunkSource().getChunk(x, 3000, ChunkStatus.FULL, false) == null,
                        "optional cold chunk read loaded a chunk from a cell task");
                boolean refused = false;
                try {
                    level.getChunk(x, 3000);
                } catch (SkeinRefusal expected) {
                    refused = true;
                }
                check(refused, "required cold chunk read was not refused on a cell task");
                check(!SuperChunkBridge.ownsChunkTasks(level), "chunk executor ownership escaped the lock");
            } finally {
                TickContext.exit(previous);
            }
        });
        for (DeferredBuffer buffer : buffers) buffer.drain(level);
        for (int i = 0; i < buffers.length; i++) {
            check(level.getChunkSource().getChunkNow(3000 + i * 4, 3000) == null, "a refused cold read left a chunk loaded");
        }
        Skein.LOGGER.info("SuperChunk cold chunk reads refused on four concurrent cell tasks, without claiming chunk tasks");
    }

    static void sample(MinecraftServer server) {
        for (var phase : TickMetrics.Phase.values()) parallel.merge(phase, TickMetrics.of(phase).parallel(), Long::sum);
        if (++samples == 20) {
            // Reload while every requested phase is active; pins must not silently turn them off.
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "skein reload");
        }
        if (!scheduled && samples >= 25) {
            for (Position position : scheduledLeaves) position.level.scheduleTick(position.pos, Blocks.BIRCH_LEAVES, 1);
            scheduled = true;
        }
    }

    private static int count(Container container) {
        int result = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) result += container.getItem(slot).getCount();
        return result;
    }

    static boolean randomLeavesFinished() {
        for (Position p : randomLeaves) if (!p.level.getBlockState(p.pos).isAir()) return false;
        return true;
    }

    static void verify() {
        for (var entry : parallel.entrySet()) check(entry.getValue() > 0, "phase never ran in parallel: " + entry.getKey());
        check(SkeinConfig.Live.entities && SkeinConfig.Live.randomTicks && SkeinConfig.Live.blockEntities
                && SkeinConfig.Live.scheduledTicks && !SkeinConfig.Live.parallelSaving, "reload lost phase policy");
        for (Position p : hoppers) {
            Container hopper = (Container) p.level.getBlockEntity(p.pos);
            Container chest = (Container) p.level.getBlockEntity(p.pos.above());
            check(hopper != null && chest != null, "inventory disappeared");
            check(count(hopper) > 0 && count(hopper) + count(chest) == 64, "hopper transfer lost, duplicated or stalled items at " + p);
        }
        for (Position p : randomLeaves) check(p.level.getBlockState(p.pos).isAir(), "random leaf failed to decay: " + p);
        for (Position p : scheduledLeaves) check(p.level.getBlockState(p.pos).is(Blocks.BIRCH_LEAVES)
                && p.level.getBlockState(p.pos).getValue(LeavesBlock.DISTANCE) == 7, "scheduled update did not run: " + p);
        String result = "PASS: all 4 in-level phases ran; " + parallel + "; 288 hopper pairs conserved items; "
                + "96 random leaves decayed; 192 scheduled leaves updated; cold chunk reads and config reload passed\n";
        try { Files.writeString(Path.of("superchunk-phases.txt"), result); }
        catch (Exception e) { throw new IllegalStateException(e); }
        Skein.LOGGER.info(result.strip());
    }
}
