package com.theproknightgamer.skein.compat;

import com.theproknightgamer.skein.Skein;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Redstone parity fuzzing ({@code -PredstoneParity}): the same seeded random circuits, hashed
 * every tick, so a run with Skein disabled and a run with it on can be compared tick by tick.
 *
 * <p>Forty-eight sealed obsidian boxes float at y=120, each holding a random circuit started by
 * observer clocks: dust, repeaters of every delay, comparators in both modes, observers, torches,
 * pistons and sticky pistons, slime, honey, lamps, note blocks, targets and trapdoors. The boxes
 * come in sixteen groups of three: a group's boxes are close enough to share a component, the
 * groups are far enough apart to be separate ones. Block drops are off, so nothing random enters
 * the world. Every tick, each box's blocks, comparator outputs and moving pistons are hashed and
 * written to {@code parity.txt}; {@code tick=N} with a box index dumps that box's blocks.
 *
 * <p>The {@code full} kit ({@code -PparityKit=full}) adds what spawns and senses entities: sand,
 * gravel and concrete powder to fall, minecarts on rails of every kind, armor stands on pressure
 * plates, tripwire lines, sculk sensors, hoppers under stocked chests, fence gates, waxed
 * copper bulbs, levers and the odd water source. Every entity in a box is hashed too, in the order
 * the level keeps them. Nothing that draws from the level's random source goes in -- dispensers,
 * droppers, TNT -- because vanilla seeds that from the clock, and nothing whose behaviour depends
 * on its entity id, like a dropped item: two vanilla runs would differ.
 */
public final class RedstoneParity {
    private static final int GROUPS = 16;
    private static final int PER_GROUP = 3;
    private static final int SIZE = 20;
    private static final int Y = 120;
    private static final int HEIGHT = 7;

    private static final List<BlockPos> boxes = new ArrayList<>();
    private static BufferedWriter out;
    private static int ticks;
    private static int total;
    private static int dumpTick = -1;
    private static int dumpBox = -1;
    private static boolean full;

    RedstoneParity() {
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, RedstoneParity::start);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, RedstoneParity::afterTick);
    }

    private static void start(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        long seed = Long.getLong("skein.test.redstoneParity.seed", 7L);
        total = Integer.getInteger("skein.test.redstoneParity.ticks", 600);
        String dump = System.getProperty("skein.test.redstoneParity.dump", "");
        if (!dump.isEmpty()) {
            String[] parts = dump.split(":");
            dumpTick = Integer.parseInt(parts[0]);
            dumpBox = Integer.parseInt(parts[1]);
        }
        full = "full".equals(System.getProperty("skein.test.redstoneParity.kit", "basic"));
        level.getGameRules().getRule(GameRules.RULE_DOBLOCKDROPS).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        RedstoneParity.seed = seed;
        for (int g = 0; g < GROUPS; g++) {
            for (int b = 0; b < PER_GROUP; b++) {
                // Groups 160 blocks apart (separate components), boxes in a group 26 apart (one component).
                BlockPos origin = new BlockPos(20000 + (g % 4) * 160 + b * 26, Y, 20000 + (g / 4) * 160);
                boxes.add(origin);
                for (int cx = (origin.getX() - 2) >> 4; cx <= (origin.getX() + SIZE + 2) >> 4; cx++) {
                    for (int cz = (origin.getZ() - 2) >> 4; cz <= (origin.getZ() + SIZE + 2) >> 4; cz++) {
                        level.setChunkForced(cx, cz, true);
                        chunks.add(net.minecraft.world.level.ChunkPos.asLong(cx, cz));
                    }
                }
            }
        }
        Skein.LOGGER.info("Redstone parity run: {} boxes, seed {}, {} ticks, {} kit; waiting for every chunk to tick",
                boxes.size(), seed, total, full ? "full" : "basic");
    }

    private static long seed;
    private static final List<Long> chunks = new ArrayList<>();
    private static boolean built;

    /** Builds every box in one tick, once every chunk they touch is block-ticking, so both runs start alike. */
    private static boolean buildWhenReady(ServerLevel level) throws IOException {
        if (built) return true;
        for (long chunk : chunks) {
            if (!level.shouldTickBlocksAt(chunk) || !level.isNaturalSpawningAllowed(new net.minecraft.world.level.ChunkPos(chunk))) return false;
        }
        Random random = new Random(seed);
        for (BlockPos origin : boxes) {
            build(level, origin, random);
        }
        built = true;
        out = Files.newBufferedWriter(Path.of("parity.txt"));
        write(level, 0);
        return false;
    }

    private static void build(ServerLevel level, BlockPos o, Random random) {
        // A sealed box: obsidian walls and ceiling, a stone floor.
        for (int x = -1; x <= SIZE; x++) {
            for (int z = -1; z <= SIZE; z++) {
                for (int y = -1; y <= HEIGHT; y++) {
                    boolean wall = x == -1 || z == -1 || x == SIZE || z == SIZE || y == HEIGHT;
                    BlockState state = y == -1 ? Blocks.STONE.defaultBlockState()
                            : wall ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    level.setBlock(o.offset(x, y, z), state, 2);
                }
            }
        }
        // Clocks first, so there is something to propagate: observer pairs facing each other.
        int clocks = 2 + random.nextInt(3);
        for (int i = 0; i < clocks; i++) {
            int x = 1 + random.nextInt(SIZE - 3);
            int z = 1 + random.nextInt(SIZE - 2);
            level.setBlock(o.offset(x, 0, z), Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.EAST), 3);
            level.setBlock(o.offset(x + 1, 0, z), Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.WEST), 3);
        }
        if (full && random.nextBoolean()) {
            // A tripwire line along x: hooks facing each other on stone supports, string between.
            int z = 1 + random.nextInt(SIZE - 2);
            int from = 1 + random.nextInt(4);
            int to = Math.min(SIZE - 2, from + 4 + random.nextInt(12));
            level.setBlock(o.offset(from - 1, 0, z), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(o.offset(to + 1, 0, z), Blocks.STONE.defaultBlockState(), 3);
            for (int x = from + 1; x < to; x++) {
                level.setBlock(o.offset(x, 0, z), Blocks.TRIPWIRE.defaultBlockState(), 3);
            }
            level.setBlock(o.offset(from, 0, z), Blocks.TRIPWIRE_HOOK.defaultBlockState()
                    .setValue(TripWireHookBlock.FACING, Direction.EAST), 3);
            level.setBlock(o.offset(to, 0, z), Blocks.TRIPWIRE_HOOK.defaultBlockState()
                    .setValue(TripWireHookBlock.FACING, Direction.WEST), 3);
        }
        // Random circuitry on the floor, and a second layer above anything solid.
        for (int layer = 0; layer < 2; layer++) {
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    BlockPos at = o.offset(x, layer, z);
                    if (!level.getBlockState(at).isAir() || random.nextInt(100) >= (layer == 0 ? 55 : 25)) {
                        continue;
                    }
                    if (layer == 1 && !level.getBlockState(at.below()).isRedstoneConductor(level, at.below())) {
                        continue;
                    }
                    BlockState state = full ? pickFull(random) : pick(random);
                    level.setBlock(at, state, 3);
                    if (full) {
                        stock(level, at, state, random);
                    }
                }
            }
        }
        if (full) {
            if (random.nextInt(4) == 0) {
                level.setBlock(o.offset(1 + random.nextInt(SIZE - 2), 2, 1 + random.nextInt(SIZE - 2)),
                        Blocks.WATER.defaultBlockState(), 3);
            }
            // No loose items: one resting on the ground moves on the ticks where its tick count plus
            // its entity id is a multiple of four, and vanilla's ids differ from run to run.
            int stands = 1 + random.nextInt(4);
            for (int i = 0; i < stands; i++) {
                BlockPos at = o.offset(random.nextInt(SIZE), random.nextInt(2), random.nextInt(SIZE));
                if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()) {
                    continue;
                }
                Entity entity = new ArmorStand(level, at.getX() + 0.5, at.getY() + 0.05, at.getZ() + 0.5);
                entity.setDeltaMovement(Vec3.ZERO);
                level.addFreshEntity(entity);
            }
        }
    }

    /** Puts something in a container just placed, and a minecart on a rail. */
    private static void stock(ServerLevel level, BlockPos at, BlockState state, Random random) {
        if (state.getBlock() instanceof BaseRailBlock && random.nextInt(3) == 0) {
            level.addFreshEntity(new Minecart(level, at.getX() + 0.5, at.getY() + 0.0625, at.getZ() + 0.5));
        } else if (level.getBlockEntity(at) instanceof Container container) {
            int stacks = random.nextInt(4);
            for (int i = 0; i < stacks; i++) {
                container.setItem(random.nextInt(container.getContainerSize()),
                        new ItemStack(random.nextBoolean() ? Items.COBBLESTONE : Items.REDSTONE, 1 + random.nextInt(64)));
            }
        }
    }

    private static BlockState pickFull(Random random) {
        Direction horizontal = Direction.from2DDataValue(random.nextInt(4));
        int roll = random.nextInt(100);
        if (roll < 62) return pick(random);
        if (roll < 66) return Blocks.SAND.defaultBlockState();
        if (roll < 68) return Blocks.GRAVEL.defaultBlockState();
        if (roll < 69) return Blocks.RED_CONCRETE_POWDER.defaultBlockState();
        if (roll < 71) return Blocks.OAK_PRESSURE_PLATE.defaultBlockState();
        if (roll < 72) return Blocks.STONE_PRESSURE_PLATE.defaultBlockState();
        if (roll < 73) return Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE.defaultBlockState();
        if (roll < 75) return Blocks.RAIL.defaultBlockState();
        if (roll < 77) return Blocks.POWERED_RAIL.defaultBlockState();
        if (roll < 79) return Blocks.DETECTOR_RAIL.defaultBlockState();
        if (roll < 80) return Blocks.ACTIVATOR_RAIL.defaultBlockState();
        if (roll < 83) return Blocks.SCULK_SENSOR.defaultBlockState();
        if (roll < 84) return Blocks.CALIBRATED_SCULK_SENSOR.defaultBlockState();
        if (roll < 87) return Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING,
                random.nextBoolean() ? Direction.DOWN : horizontal);
        if (roll < 89) return Blocks.CHEST.defaultBlockState();
        if (roll < 91) return Blocks.WAXED_COPPER_BULB.defaultBlockState();
        if (roll < 93) return Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, horizontal);
        if (roll < 95) return Blocks.LEVER.defaultBlockState().setValue(LeverBlock.POWERED, random.nextBoolean());
        return Blocks.STONE.defaultBlockState();
    }

    private static BlockState pick(Random random) {
        Direction horizontal = Direction.from2DDataValue(random.nextInt(4));
        Direction any = Direction.values()[random.nextInt(6)];
        int roll = random.nextInt(100);
        if (roll < 30) return Blocks.REDSTONE_WIRE.defaultBlockState();
        if (roll < 40) return Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, horizontal)
                .setValue(RepeaterBlock.DELAY, 1 + random.nextInt(4));
        if (roll < 45) return Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, horizontal)
                .setValue(ComparatorBlock.MODE, random.nextBoolean() ? ComparatorMode.COMPARE : ComparatorMode.SUBTRACT);
        if (roll < 53) return Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, any);
        if (roll < 57) return Blocks.REDSTONE_TORCH.defaultBlockState();
        if (roll < 68) return Blocks.STONE.defaultBlockState();
        if (roll < 72) return Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, any);
        if (roll < 76) return Blocks.STICKY_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, any);
        if (roll < 80) return Blocks.SLIME_BLOCK.defaultBlockState();
        if (roll < 82) return Blocks.HONEY_BLOCK.defaultBlockState();
        if (roll < 86) return Blocks.REDSTONE_LAMP.defaultBlockState();
        if (roll < 89) return Blocks.NOTE_BLOCK.defaultBlockState();
        if (roll < 91) return Blocks.TARGET.defaultBlockState();
        if (roll < 94) return Blocks.OAK_TRAPDOOR.defaultBlockState();
        if (roll < 96) return Blocks.REDSTONE_BLOCK.defaultBlockState();
        return Blocks.GLASS.defaultBlockState();
    }

    private static void afterTick(ServerTickEvent.Post event) {
        if (boxes.isEmpty()) return;
        ServerLevel level = event.getServer().overworld();
        try {
            if (!buildWhenReady(level) || out == null) return;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        ticks++;
        try {
            write(level, ticks);
            if (ticks >= total) {
                out.close();
                out = null;
                MinecraftServer server = event.getServer();
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "skein status");
                long finished = 0;
                try {
                    java.lang.reflect.Field field = Class.forName("com.theproknightgamer.skein.core.OrderedGroups")
                            .getDeclaredField("FINISHED_IN_ORDER");
                    field.setAccessible(true);
                    finished = ((java.util.concurrent.atomic.AtomicLong) field.get(null)).get();
                } catch (ReflectiveOperationException ignored) {
                    // an older Skein
                }
                Files.writeString(Path.of("result.txt"), "PASS: recorded " + total + " ticks of " + boxes.size()
                        + " boxes; " + finished + " scheduled ticks finished in order on the coordinator\n");
                Skein.LOGGER.info("Redstone parity run recorded {} ticks", total);
                event.getServer().halt(false);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Every pending block tick in the order vanilla will run them -- trigger tick, priority, then
     * sub-tick number -- reduced to positions and types. A run that numbers new ticks in a
     * different order than vanilla shows up here before it shows up in any block.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static long pendingOrder(ServerLevel level, int at) throws IOException {
        try {
            if (containersField == null) {
                containersField = net.minecraft.world.ticks.LevelTicks.class.getDeclaredField("allContainers");
                containersField.setAccessible(true);
            }
            List<net.minecraft.world.ticks.ScheduledTick<?>> all = new ArrayList<>();
            for (Object ticks : new Object[] {level.getBlockTicks(), level.getFluidTicks()}) {
                it.unimi.dsi.fastutil.longs.Long2ObjectMap<net.minecraft.world.ticks.LevelChunkTicks> containers =
                        (it.unimi.dsi.fastutil.longs.Long2ObjectMap) containersField.get(ticks);
                for (net.minecraft.world.ticks.LevelChunkTicks container : containers.values()) {
                    container.getAll().forEach(t -> {
                        net.minecraft.world.ticks.ScheduledTick<?> tick = (net.minecraft.world.ticks.ScheduledTick<?>) t;
                        if (inBoxes(tick.pos())) all.add(tick);
                    });
                }
            }
            all.sort(net.minecraft.world.ticks.ScheduledTick.DRAIN_ORDER);
            long now = level.getGameTime();
            if (at == dumpTick) {
                List<String> lines = new ArrayList<>();
                long first = all.isEmpty() ? 0 : all.stream().mapToLong(t -> t.subTickOrder()).min().getAsLong();
                for (net.minecraft.world.ticks.ScheduledTick<?> t : all) {
                    lines.add(t.pos().toShortString() + " " + t.type() + " +" + (t.triggerTick() - now) + " "
                            + t.priority() + " #" + (t.subTickOrder() - first));
                }
                Files.write(Path.of("parity-pending.txt"), lines);
            }
            long h = 7;
            for (net.minecraft.world.ticks.ScheduledTick<?> t : all) {
                h = h * 6364136223846793005L + t.pos().asLong();
                h = h * 31 + (t.type() instanceof Block block ? Block.getId(block.defaultBlockState())
                        : BuiltInRegistries.FLUID.getId((net.minecraft.world.level.material.Fluid) t.type()) + 100000);
                h = h * 31 + (t.triggerTick() - now) * 7 + t.priority().getValue();
            }
            return h * 31 + all.size();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.lang.reflect.Field containersField;

    private static boolean inBoxes(BlockPos pos) {
        for (BlockPos o : boxes) {
            if (pos.getX() >= o.getX() && pos.getX() < o.getX() + SIZE && pos.getZ() >= o.getZ()
                    && pos.getZ() < o.getZ() + SIZE && pos.getY() >= o.getY() && pos.getY() < o.getY() + HEIGHT) {
                return true;
            }
        }
        return false;
    }

    /** Every entity in a box, in the order the level keeps them: type, position, motion, and what it carries. */
    private static long entities(ServerLevel level, int at) throws IOException {
        long h = 17;
        List<String> lines = at == dumpTick ? new ArrayList<>() : null;
        for (Entity entity : level.getEntities().getAll()) {
            if (!inBoxes(entity.blockPosition())) continue;
            long v = BuiltInRegistries.ENTITY_TYPE.getId(entity.getType());
            Vec3 pos = entity.position();
            Vec3 motion = entity.getDeltaMovement();
            for (double d : new double[] {pos.x, pos.y, pos.z, motion.x, motion.y, motion.z}) {
                v = v * 31 + Double.doubleToLongBits(d);
            }
            if (entity instanceof FallingBlockEntity falling) {
                v = v * 31 + Block.getId(falling.getBlockState());
            } else if (entity instanceof ItemEntity item) {
                v = v * 31 + BuiltInRegistries.ITEM.getId(item.getItem().getItem()) * 101L + item.getItem().getCount();
            }
            h = h * 6364136223846793005L + v;
            if (lines != null) {
                lines.add(entity.getType() + " " + pos + " " + motion
                        + (entity instanceof FallingBlockEntity f ? " " + f.getBlockState() : "")
                        + (entity instanceof ItemEntity i ? " " + i.getItem() : ""));
            }
        }
        if (lines != null) {
            Files.write(Path.of("parity-entities.txt"), lines);
        }
        return h;
    }

    private static void write(ServerLevel level, int tick) throws IOException {
        StringBuilder line = new StringBuilder().append(tick).append(" q").append(Long.toHexString(pendingOrder(level, tick)))
                .append(" e").append(Long.toHexString(entities(level, tick)));
        for (int i = 0; i < boxes.size(); i++) {
            line.append(' ').append(Long.toHexString(hash(level, boxes.get(i), tick == dumpTick && i == dumpBox)));
        }
        out.write(line.append('\n').toString());
    }

    private static long hash(ServerLevel level, BlockPos o, boolean dump) throws IOException {
        long h = 1125899906842597L;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        List<String> lines = dump ? new ArrayList<>() : null;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    at.set(o.getX() + x, o.getY() + y, o.getZ() + z);
                    BlockState state = level.getBlockState(at);
                    long v = Block.getId(state);
                    BlockEntity be = level.getBlockEntity(at);
                    String extra = "";
                    if (be instanceof ComparatorBlockEntity comparator) {
                        v = v * 31 + comparator.getOutputSignal();
                        extra = " out=" + comparator.getOutputSignal();
                    } else if (be instanceof Container container) {
                        StringBuilder items = new StringBuilder();
                        for (int slot = 0; slot < container.getContainerSize(); slot++) {
                            ItemStack stack = container.getItem(slot);
                            v = v * 31 + BuiltInRegistries.ITEM.getId(stack.getItem()) * 101L + stack.getCount();
                            if (!stack.isEmpty()) items.append(' ').append(slot).append('=').append(stack);
                        }
                        extra = items.toString();
                    } else if (be instanceof PistonMovingBlockEntity moving) {
                        v = v * 31 + Block.getId(moving.getMovedState());
                        v = v * 31 + Float.floatToIntBits(moving.getProgress(0.0F));
                        v = v * 31 + moving.getDirection().ordinal() * 2 + (moving.isExtending() ? 1 : 0);
                        extra = " moving=" + moving.getMovedState() + " progress=" + moving.getProgress(0.0F)
                                + " dir=" + moving.getDirection() + " ext=" + moving.isExtending();
                    }
                    h = h * 6364136223846793005L + v + 1442695040888963407L;
                    if (lines != null && !state.isAir()) {
                        lines.add(x + "," + y + "," + z + " " + state + extra);
                    }
                }
            }
        }
        if (lines != null) {
            Files.write(Path.of("parity-dump.txt"), lines);
        }
        return h;
    }
}
