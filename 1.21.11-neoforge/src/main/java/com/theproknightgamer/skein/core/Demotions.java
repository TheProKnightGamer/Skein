package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entity, block entity, block and fluid types sent back to the level-owning
 * thread after one of them threw during a parallel tick. Blocks and fluids are
 * their random ticks: those of a demoted type wait for the end of the pass.
 *
 * <p>A type that throws on a worker and not on the level thread is almost
 * always a mod keeping state that is not safe to share: a static map, a
 * network registry, a lazily built cache. Logging the failure and carrying on
 * would repeat it every tick, and every tick it fails the machine or the mob
 * does nothing. Moving the whole type out of the parallel set turns that into
 * one line in the log and a little lost speed. From then on the type ticks
 * where vanilla ticks it, through vanilla's own error handling, so a bug that
 * has nothing to do with threading still shows up exactly as it would without
 * Skein.
 *
 * <p>A race can also corrupt a map so that the next thread into it spins
 * forever. Nothing can stop that thread, so the server's watchdog ends the run;
 * what Skein can do is notice which type the stuck worker was ticking and demote
 * that. Every demotion is written to {@code config/skein-demoted.txt} and read
 * back at the next start, so a type that failed once is not given the chance
 * to fail, or hang, again. {@code /skein unquarantine} empties the file.
 *
 * <p>What this cannot catch is a race that corrupts state without throwing or
 * hanging; the blacklists remain for those. It also attributes a failure to the
 * type that was ticking, which is usually but not always the type at fault.
 */
public final class Demotions {
    private Demotions() {}

    private static final Set<EntityType<?>> ENTITIES = ConcurrentHashMap.newKeySet();
    private static final Set<BlockEntityType<?>> BLOCK_ENTITIES = ConcurrentHashMap.newKeySet();
    private static final Set<Block> BLOCKS = ConcurrentHashMap.newKeySet();
    private static final Set<Fluid> FLUIDS = ConcurrentHashMap.newKeySet();

    /** Relative to the server's directory, beside Skein's own config. */
    private static final Path FILE = Path.of("config", "skein-demoted.txt");
    private static final Object FILE_LOCK = new Object();

    public static boolean entity(EntityType<?> type) {
        return !ENTITIES.isEmpty() && ENTITIES.contains(type);
    }

    public static boolean anyBlockEntities() {
        return !BLOCK_ENTITIES.isEmpty();
    }

    public static boolean blockEntity(BlockEntityType<?> type) {
        return !BLOCK_ENTITIES.isEmpty() && BLOCK_ENTITIES.contains(type);
    }

    public static boolean block(Block block) {
        return !BLOCKS.isEmpty() && BLOCKS.contains(block);
    }

    public static boolean fluid(Fluid fluid) {
        return !FLUIDS.isEmpty() && FLUIDS.contains(fluid);
    }

    /** A block's random tick threw inside a parallel pass. */
    public static void blockThrew(Block block, ServerLevel level, Throwable thrown) {
        if (BLOCKS.add(block)) {
            remember("block", BuiltInRegistries.BLOCK.getKey(block));
            announceRandomTicks("block", BuiltInRegistries.BLOCK.getKey(block), level, "threw: " + thrown);
        }
    }

    /** A fluid's random tick threw inside a parallel pass. */
    public static void fluidThrew(Fluid fluid, ServerLevel level, Throwable thrown) {
        if (FLUIDS.add(fluid)) {
            remember("fluid", BuiltInRegistries.FLUID.getKey(fluid));
            announceRandomTicks("fluid", BuiltInRegistries.FLUID.getKey(fluid), level, "threw: " + thrown);
        }
    }

    /**
     * Records that {@code item} threw during a parallel tick in {@code level}.
     * Anything that is not an entity or a block entity Skein can see is left
     * as it was.
     */
    public static void threw(Object item, ServerLevel level, Throwable thrown) {
        demote(item, level, "threw while ticking in parallel", "The failure was: " + thrown);
    }

    /**
     * Records that {@code item} has been ticking on a worker for so long that
     * the phase stalled: in practice a map corrupted by a race, which the
     * worker will now spin in forever. Called from the thread reporting the
     * stall, which is the only one still able to write anything down.
     */
    public static void hung(Object item, Level level) {
        if (item instanceof Block block) {
            if (BLOCKS.add(block)) {
                remember("block", BuiltInRegistries.BLOCK.getKey(block));
                announceRandomTicks("block", BuiltInRegistries.BLOCK.getKey(block), level, "hung");
            }
            return;
        }
        if (item instanceof Fluid fluid) {
            if (FLUIDS.add(fluid)) {
                remember("fluid", BuiltInRegistries.FLUID.getKey(fluid));
                announceRandomTicks("fluid", BuiltInRegistries.FLUID.getKey(fluid), level, "hung");
            }
            return;
        }
        demote(item, level, "hung while ticking in parallel",
                "Nothing can stop the stuck worker; the server has to be restarted, and this type will tick on "
                        + "the level thread from then on.");
    }

    /**
     * For Skein's guard nearest one entity, block entity or random tick:
     * whether it has dealt with {@code thrown}, or the caller should rethrow
     * it.
     *
     * <p>Nearest, because other mods wrap the same calls. Neruina catches
     * everything thrown there and suspends the entity, block entity or block
     * that threw. A refusal is Skein's own, and a failure inside a parallel
     * pass is Skein's to judge, so neither should reach them: the refusal ends
     * this one tick, the failure demotes the type, and that type's next
     * failure, on the level thread, goes to them exactly as without Skein.
     */
    public static boolean absorb(Object item, Level level, Throwable thrown) {
        if (SkeinRefusal.in(thrown)) {
            // Whatever bracket the refusal unwound through is still open.
            TickContext.resetBrackets();
            SkeinRefusal.swallowed(item, thrown);
            return true;
        }
        if (!TickContext.deferring() || !(level instanceof ServerLevel serverLevel)
                || SkeinConfig.Live.crashPolicy == SkeinConfig.CrashPolicy.RETHROW) {
            return false;
        }
        TickMetrics.recordFailure();
        TickContext.resetBrackets();
        Skein.LOGGER.error("Skein: {} threw during a parallel tick in {}", describe(item),
                serverLevel.dimension().identifier(), thrown);
        threw(item, serverLevel, thrown);
        return true;
    }

    private static void demote(Object item, Level level, String what, String detail) {
        if (item instanceof Entity entity) {
            EntityType<?> type = entity.getType();
            if (ENTITIES.add(type)) {
                remember("entity", BuiltInRegistries.ENTITY_TYPE.getKey(type));
                announce("entity", BuiltInRegistries.ENTITY_TYPE.getKey(type), what, level, detail);
            }
            return;
        }
        BlockEntity blockEntity = blockEntityOf(item);
        if (blockEntity != null && BLOCK_ENTITIES.add(blockEntity.getType())) {
            remember("block_entity", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()));
            announce("block entity", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()), what,
                    level, detail);
        }
    }

    private static BlockEntity blockEntityOf(Object item) {
        return item instanceof SkeinTicker ticker ? ticker.skein$blockEntity()
                : item instanceof BlockEntity blockEntity ? blockEntity : null;
    }

    /** What a log line calls the thing that failed. */
    public static String describe(Object item) {
        BlockEntity blockEntity = blockEntityOf(item);
        if (blockEntity != null) {
            return BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()) + " at "
                    + blockEntity.getBlockPos().toShortString();
        }
        if (item instanceof Block block) return String.valueOf(BuiltInRegistries.BLOCK.getKey(block));
        if (item instanceof Fluid fluid) return String.valueOf(BuiltInRegistries.FLUID.getKey(fluid));
        return String.valueOf(item);
    }

    private static void announceRandomTicks(String what, Object id, Level level, String how) {
        Skein.LOGGER.warn("Skein: {} {} {} during a random tick in parallel in {}. Its random ticks now wait for "
                        + "the end of each parallel pass, where they run exactly as without Skein; this is kept in "
                        + "{} for later runs too. Run /skein unquarantine to try them in parallel again.",
                what, id, how, level.dimension().identifier(), FILE);
    }

    private static void announce(String what, Object id, String how, Level level, String detail) {
        Skein.LOGGER.warn("Skein: {} type {} {} in {}. Every {} now ticks on the level thread, where it runs "
                        + "exactly as without Skein; this is kept in {} for later runs too. Run /skein unquarantine "
                        + "to try it in parallel again. {}",
                what, id, how, level.dimension().identifier(), id, FILE, detail);
    }

    /**
     * Reads back what earlier runs demoted. Ids nothing is registered under
     * any more stay in the file, in case their mod comes back.
     */
    public static void load() {
        List<String> lines;
        synchronized (FILE_LOCK) {
            if (!Files.exists(FILE)) {
                return;
            }
            try {
                lines = Files.readAllLines(FILE, StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                Skein.LOGGER.warn("Skein: could not read {}: {}", FILE, unreadable.toString());
                return;
            }
        }
        List<String> loaded = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 2 || parts[0].startsWith("#")) {
                continue;
            }
            Identifier id = Identifier.tryParse(parts[1]);
            if (id == null) {
                continue;
            }
            boolean known = switch (parts[0]) {
                case "entity" -> add(BuiltInRegistries.ENTITY_TYPE, id, ENTITIES);
                case "block_entity" -> add(BuiltInRegistries.BLOCK_ENTITY_TYPE, id, BLOCK_ENTITIES);
                case "block" -> add(BuiltInRegistries.BLOCK, id, BLOCKS);
                case "fluid" -> add(BuiltInRegistries.FLUID, id, FLUIDS);
                default -> false;
            };
            if (known) {
                loaded.add(parts[1]);
            }
        }
        if (!loaded.isEmpty()) {
            Skein.LOGGER.info("Skein: {} type(s) failed in parallel on an earlier run and tick on the level thread "
                    + "({}; /skein unquarantine to retry): {}", loaded.size(), FILE, loaded);
        }
    }

    private static <T> boolean add(Registry<T> registry, Identifier id, Set<T> into) {
        T value = registry.getOptional(id).orElse(null);
        return value != null && into.add(value);
    }

    private static void remember(String kind, Object id) {
        synchronized (FILE_LOCK) {
            try {
                Files.createDirectories(FILE.getParent());
                boolean fresh = !Files.exists(FILE);
                String header = fresh ? "# Types Skein has moved off its worker threads after they threw or hung\n"
                        + "# there. Read at every start; /skein unquarantine empties it.\n" : "";
                Files.writeString(FILE, header + kind + " " + id + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException unwritable) {
                Skein.LOGGER.warn("Skein: could not write {}; the demotion of {} lasts until the server stops: {}",
                        FILE, id, unwritable.toString());
            }
        }
    }

    /** What has been demoted, for {@code /skein status}. */
    public static List<String> describe() {
        List<String> out = new ArrayList<>();
        for (EntityType<?> type : ENTITIES) out.add(String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(type)));
        for (BlockEntityType<?> type : BLOCK_ENTITIES) {
            out.add(String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type)));
        }
        for (Block block : BLOCKS) out.add(BuiltInRegistries.BLOCK.getKey(block) + " (random ticks)");
        for (Fluid fluid : FLUIDS) out.add(BuiltInRegistries.FLUID.getKey(fluid) + " (random ticks)");
        out.sort(null);
        return out;
    }

    /** Gives every demoted type another chance. Returns how many there were. */
    public static int clear() {
        int count = ENTITIES.size() + BLOCK_ENTITIES.size() + BLOCKS.size() + FLUIDS.size();
        ENTITIES.clear();
        BLOCK_ENTITIES.clear();
        BLOCKS.clear();
        FLUIDS.clear();
        synchronized (FILE_LOCK) {
            try {
                Files.deleteIfExists(FILE);
            } catch (IOException undeletable) {
                Skein.LOGGER.warn("Skein: could not delete {}; its types will be demoted again at the next start: {}",
                        FILE, undeletable.toString());
            }
        }
        return count;
    }
}
