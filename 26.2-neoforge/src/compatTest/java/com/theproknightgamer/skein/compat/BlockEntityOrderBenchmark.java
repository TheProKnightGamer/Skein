package com.theproknightgamer.skein.compat;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Block entities in vanilla's order against the grid, in one server ({@code -PbeOrderBench}).
 *
 * <p>A dedicated-server A/B on a busy machine measures the machine: two builds a few minutes apart
 * see different loads. Here one world holds both workloads and the server switches between the
 * ways of ticking them every twenty ticks, so each sees the same load at the same moments.
 *
 * <p>The world: 81 stacks of eight rings of four hoppers passing items round, 28 blocks apart over
 * 16x16 chunks -- each stack a group of its own -- and one solid 40x40 block of hoppers in chains, a
 * dense build bigger than {@code vanillaOrderLimit}, which takes the grid fallback -- and 64 fields of
 * 36 empty hoppers facing down, 32 blocks apart, which look for items above them every tick like a
 * farm's collectors: the part of the phase that is worth running in parallel. The modes are
 * groups in vanilla's order (the default), the grid ({@code vanillaOrderLimit = 0}), and the phase
 * switched off. Each tick of the overworld is timed from its start to its end; the first two ticks
 * after a switch are dropped. Results go to {@code be-order.txt}.
 */
public final class BlockEntityOrderBenchmark {
    private static final int Y = 120;
    private static final int RINGS_X = 30000, RINGS_Z = 30000, RINGS_CHUNKS = 16, SPACING = 28, LAYERS = 8;
    private static final int DENSE_X = 31000, DENSE_Z = 31000, DENSE = 40;
    /** Fields of empty hoppers facing down, which look for items above them every tick, like a farm's collectors. */
    private static final int FIELDS_X = 32000, FIELDS_Z = 32000, FIELDS_CHUNKS = 16, FIELD = 6, FIELD_SPACING = 32;
    private static final String[] MODES = {"ordered", "grid", "serial"};
    private static final int PERIOD = 20;

    private final List<Long> chunks = new ArrayList<>();
    private final List<long[]> samples = new ArrayList<>();
    private int sampleCount;
    private int warmup = 200;
    private int total = Integer.getInteger("skein.test.beOrderBench.ticks", 3000);
    private boolean built;
    private int ticks;
    private long started;
    private int mode;

    BlockEntityOrderBenchmark() {
        for (int i = 0; i < MODES.length; i++) {
            this.samples.add(new long[this.total]);
        }
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, this::start);
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Pre.class, this::before);
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post.class, this::after);
    }

    private void start(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        level.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
        level.getGameRules().set(GameRules.SPAWN_MONSTERS, false, server);
        int rx = RINGS_X >> 4, rz = RINGS_Z >> 4;
        for (int cx = rx; cx < rx + RINGS_CHUNKS; cx++) {
            for (int cz = rz; cz < rz + RINGS_CHUNKS; cz++) {
                force(level, cx, cz);
            }
        }
        for (int cx = FIELDS_X >> 4; cx < (FIELDS_X >> 4) + FIELDS_CHUNKS; cx++) {
            for (int cz = FIELDS_Z >> 4; cz < (FIELDS_Z >> 4) + FIELDS_CHUNKS; cz++) {
                force(level, cx, cz);
            }
        }
        for (int cx = DENSE_X >> 4; cx <= (DENSE_X + DENSE) >> 4; cx++) {
            for (int cz = DENSE_Z >> 4; cz <= (DENSE_Z + DENSE) >> 4; cz++) {
                force(level, cx, cz);
            }
        }
        Skein.LOGGER.info("Block entity order benchmark: waiting for {} chunks", this.chunks.size());
    }

    private void force(ServerLevel level, int cx, int cz) {
        level.setChunkForced(cx, cz, true);
        this.chunks.add(net.minecraft.world.level.ChunkPos.pack(cx, cz));
    }

    private boolean ready(ServerLevel level) {
        for (long chunk : this.chunks) {
            if (!level.shouldTickBlocksAt(chunk)) return false;
        }
        return true;
    }

    private void build(ServerLevel level) {
        int rings = 0;
        for (int x = RINGS_X + 2; x + 2 < RINGS_X + RINGS_CHUNKS * 16; x += SPACING) {
            for (int z = RINGS_Z + 2; z + 2 < RINGS_Z + RINGS_CHUNKS * 16; z += SPACING) {
                for (int y = Y; y < Y + LAYERS; y++) {
                    hopper(level, x, y, z, Direction.EAST);
                    hopper(level, x + 1, y, z, Direction.SOUTH);
                    hopper(level, x + 1, y, z + 1, Direction.WEST);
                    hopper(level, x, y, z + 1, Direction.NORTH);
                    rings++;
                }
            }
        }
        for (int x = DENSE_X; x < DENSE_X + DENSE; x++) {
            for (int z = DENSE_Z; z < DENSE_Z + DENSE; z++) {
                hopper(level, x, Y, z, Direction.EAST);
            }
        }
        int fields = 0;
        for (int x = FIELDS_X + 2; x + FIELD < FIELDS_X + FIELDS_CHUNKS * 16; x += FIELD_SPACING) {
            for (int z = FIELDS_Z + 2; z + FIELD < FIELDS_Z + FIELDS_CHUNKS * 16; z += FIELD_SPACING) {
                for (int dx = 0; dx < FIELD; dx++) {
                    for (int dz = 0; dz < FIELD; dz++) {
                        level.setBlock(new BlockPos(x + dx, Y, z + dz),
                                Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN), 2);
                    }
                }
                fields++;
            }
        }
        for (int z = DENSE_Z; z < DENSE_Z + DENSE; z++) {
            level.setBlock(new BlockPos(DENSE_X + DENSE, Y, z), Blocks.CHEST.defaultBlockState(), 3);
        }
        Skein.LOGGER.info("Block entity order benchmark: {} rings, {} dense hoppers, {} collector fields of {}",
                rings, DENSE * DENSE, fields, FIELD * FIELD);
    }

    private static void hopper(ServerLevel level, int x, int y, int z, Direction facing) {
        BlockPos pos = new BlockPos(x, y, z);
        level.setBlock(pos, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, facing), 2);
        if (level.getBlockEntity(pos) instanceof HopperBlockEntity hopper) {
            hopper.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        }
    }

    private void before(LevelTickEvent.Pre event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            this.started = System.nanoTime();
        }
    }

    private void after(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        long elapsed = System.nanoTime() - this.started;
        if (!this.built) {
            if (ready(level)) {
                build(level);
                this.built = true;
            }
            return;
        }
        if (this.warmup > 0) {
            this.warmup--;
            if (this.warmup == 0) {
                apply(0);
            }
            return;
        }
        int phase = this.ticks % PERIOD;
        if (phase >= 2) {
            this.samples.get(this.mode)[this.ticks] = elapsed;
        }
        this.ticks++;
        if (this.ticks % PERIOD == 0) {
            apply((this.ticks / PERIOD) % MODES.length);
        }
        if (this.ticks >= this.total) {
            report(level.getServer());
        }
    }

    private void apply(int next) {
        this.mode = next;
        SkeinConfig.Live.blockEntities = !MODES[next].equals("serial");
        SkeinConfig.Live.blockEntityOrderLimit = MODES[next].equals("grid") ? 0 : 512;
    }

    private void report(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        for (int m = 0; m < MODES.length; m++) {
            long[] all = Arrays.stream(this.samples.get(m)).filter(v -> v > 0).sorted().toArray();
            double mean = Arrays.stream(all).average().orElse(0) / 1e6;
            String line = String.format("%-8s n=%d median=%.3fms mean=%.3fms p10=%.3fms p90=%.3fms", MODES[m], all.length,
                    all.length == 0 ? 0 : all[all.length / 2] / 1e6, mean,
                    all.length == 0 ? 0 : all[all.length / 10] / 1e6, all.length == 0 ? 0 : all[all.length * 9 / 10] / 1e6);
            lines.add(line);
            Skein.LOGGER.info("Block entity order benchmark: {}", line);
        }
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "skein status");
        try {
            Files.write(Path.of("be-order.txt"), lines);
            Files.writeString(Path.of("result.txt"), "PASS: block entity order benchmark\n");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.halt(false);
    }
}
