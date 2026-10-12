package com.theproknightgamer.skein;

import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.core.Grid;
import com.theproknightgamer.skein.core.HazardWatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Config, plus a plain-field snapshot of it.
 *
 * <p>{@link ModConfigSpec.ConfigValue#get()} walks a NightConfig tree on every
 * call. That is fine for a menu toggle and completely unacceptable in
 * {@code ServerChunkCache#getChunkNow}, which some of these values gate. So the
 * spec is the source of truth on disk and {@link Live} is what the hot paths
 * read; {@link #refresh()} copies one into the other whenever the config loads
 * or reloads, on the server thread between ticks.
 */
public final class SkeinConfig {
    private SkeinConfig() {}

    public enum CrashPolicy {
        /** Pull the offending dimension out of the parallel set and keep the server up. */
        DISABLE,
        /** Re-throw on the server thread, producing a normal vanilla crash report. */
        RETHROW
    }

    /**
     * The split lays four half-offset grids over the world so that every
     * position is interior to one of them. That only holds while a cell is at
     * least four margins across, so the cell size is raised to meet the margin
     * rather than being allowed to silently push work into the serial set. See
     * {@link Grid}.
     */
    private static final int MARGINS_PER_REGION = 4;

    /**
     * The widest footprint anything is filed into the grid by: a randomly-ticked
     * chunk, which is a 16-block span rather than a position. Four margins is
     * the answer for a point, and a span needs room for its own width on top of
     * that. See {@link Grid}.
     */
    private static final int WIDEST_FOOTPRINT = 16;

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.BooleanValue ENABLED;
    private static final ModConfigSpec.IntValue THREAD_COUNT;
    private static final ModConfigSpec.BooleanValue USE_ALL_CPU_THREADS;
    private static final ModConfigSpec.EnumValue<CrashPolicy> CRASH_POLICY;
    private static final ModConfigSpec.BooleanValue THREAD_SAFE_RANDOM;
    private static final ModConfigSpec.BooleanValue IGNORE_MOD_CONFLICTS;
    private static final ModConfigSpec.IntValue LOG_EVERY_TICKS;

    private static final ModConfigSpec.BooleanValue DIMENSIONS_ENABLED;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> DIMENSION_BLACKLIST;

    private static final ModConfigSpec.IntValue REGION_CHUNKS;
    private static final ModConfigSpec.IntValue REGION_MARGIN;

    /**
     * Types admitted to the cluster tier: point-like, short-reach, and unable
     * to write blocks. Each was read end to end -- tick, {@code onHit},
     * {@code entityInside} of what it rides -- and none reaches further than
     * the default {@code clusterReach} once the game event is parked.
     */
    private static final List<String> CLUSTERABLE_ENTITIES = List.of(
            "minecraft:snowball=0",
            "minecraft:egg=0",
            "minecraft:item=2",
            "minecraft:experience_orb=2");

    private static final ModConfigSpec.BooleanValue ADAPTIVE;
    private static final ModConfigSpec.BooleanValue ENTITIES_ENABLED;
    private static final ModConfigSpec.BooleanValue THREADED_PROJECTILE_QUERIES;
    private static final ModConfigSpec.BooleanValue COLLISION_INDEX;
    private static final ModConfigSpec.BooleanValue PARALLEL_PISTON_PUSHES;
    private static final ModConfigSpec.BooleanValue PARALLEL_EXPLOSION_RAYS;
    private static final ModConfigSpec.BooleanValue THREADED_ENTITY_TRACKING;
    private static final ModConfigSpec.IntValue ENTITY_MIN_COUNT;
    private static final ModConfigSpec.BooleanValue ENTITY_PIN_PLAYERS;
    private static final ModConfigSpec.IntValue ENTITY_LOADED_MARGIN;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> ENTITY_BLACKLIST;
    private static final ModConfigSpec.BooleanValue ENTITY_CLUSTER_ENABLED;
    private static final ModConfigSpec.DoubleValue ENTITY_CLUSTER_REACH;
    private static final ModConfigSpec.IntValue ENTITY_CLUSTER_MIN;
    private static final ModConfigSpec.DoubleValue ENTITY_CLUSTER_ISOLATION;
    private static final ModConfigSpec.BooleanValue ENTITY_CLUSTER_EXACT_PROJECTILES;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> ENTITY_CLUSTER_TYPES;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> ENTITY_CLUSTER_INERT;

    private static final ModConfigSpec.BooleanValue RANDOM_TICKS_ENABLED;
    private static final ModConfigSpec.IntValue RANDOM_TICK_MIN_CHUNKS;
    private static final ModConfigSpec.BooleanValue PARALLEL_CHUNK_SCAN;

    private static final ModConfigSpec.BooleanValue SCHEDULED_TICKS_ENABLED;
    private static final ModConfigSpec.IntValue SCHEDULED_TICK_MIN_COUNT;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> SCHEDULED_TICK_BLACKLIST;

    private static final ModConfigSpec.BooleanValue BLOCK_ENTITIES_ENABLED;
    private static final ModConfigSpec.IntValue BLOCK_ENTITY_MIN_COUNT;
    private static final ModConfigSpec.IntValue BLOCK_ENTITY_ORDER_LIMIT;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> BLOCK_ENTITY_BLACKLIST;
    private static final ModConfigSpec.BooleanValue SAVING_ENABLED;
    private static final ModConfigSpec.IntValue SAVING_MIN_CHUNKS;

    /**
     * Vanilla block entities whose tick reaches further than any sane margin:
     * beacons and conduits look for players tens of blocks away and add effects
     * to them, bells make raiders glow at forty-eight, and end gateways
     * teleport entities into another dimension outright.
     */
    private static final List<String> LONG_REACH_BLOCK_ENTITIES = List.of(
            "minecraft:beacon",
            "minecraft:conduit",
            "minecraft:bell",
            "minecraft:end_gateway");

    /**
     * Blocks whose scheduled tick can reach anywhere at all: a command block
     * runs an arbitrary command. No margin bounds that, so its presence turns
     * the whole container back over to vanilla for that tick.
     *
     * <p>A structure block reaches just as far and is deliberately not here.
     * It has no tick of its own -- it places from a neighbour update -- so no
     * collected tick set ever names it and no entry here could match. Its
     * placement is parked for the barrier instead, which covers every phase
     * rather than this one. See {@code StructureBlockEntityMixin}.
     */
    private static final List<String> UNBOUNDED_SCHEDULED_TICKS = List.of(
            "minecraft:command_block",
            "minecraft:chain_command_block",
            "minecraft:repeating_command_block");

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Skein - parallel dimension, entity, random-tick and block-entity ticking.").push("general");
        ENABLED = builder
                .comment("Master switch. When false Skein loads but every mixin falls straight through to vanilla.")
                .define("enabled", true);
        THREAD_COUNT = builder
                .comment("Worker threads. 0 auto-sizes to (available processors - 1), clamped to [1, 16].",
                        "The server thread coordinates and helps, so this is workers *in addition* to it.",
                        "Ignored when useAllCpuThreads is true. Restart the server/world to apply.")
                .worldRestart()
                .defineInRange("threadCount", 0, 0, 64);
        USE_ALL_CPU_THREADS = builder
                .comment("Allow Skein to use all logical CPU processors available to Java when there is enough parallel work.",
                        "Overrides threadCount and removes the automatic 16-worker cap.",
                        "Creates (available processors - 1) workers, minimum 1; the server thread also runs work.",
                        "Adaptive scheduling still chooses when parallel work is useful; this does not force 100% CPU usage.",
                        "Restart the server/world to apply.")
                .worldRestart()
                .define("useAllCpuThreads", false);
        CRASH_POLICY = builder
                .comment("What to do when a parallel-ticked dimension throws.",
                        "DISABLE: log it, drop that dimension back to serial ticking, keep the server alive.",
                        "RETHROW: hand the throwable back to the server thread for a normal crash report.")
                .defineEnum("crashPolicy", CrashPolicy.DISABLE);
        THREAD_SAFE_RANDOM = builder
                .comment("Give each worker its own draw from Level.random while a parallel pass is open.",
                        "Vanilla's source has an atomic seed but a shared gaussian cache, which two",
                        "threads will hand each other the same number out of. Outside a pass every",
                        "draw still comes from the level's own source either way.")
                .define("threadSafeLevelRandom", true);
        ADAPTIVE = builder
                .comment("Time each phase both ways and take whichever is actually cheaper.",
                        "Splitting work costs a barrier, a per-cell context and a replay of whatever",
                        "the pass deferred. On a busy level that disappears into the win; on a quiet",
                        "one it is the entire cost, and a count-based threshold cannot tell the",
                        "difference -- two thousand hoppers clear any sane count and still do not add",
                        "up to enough work to pay for a barrier.",
                        "With this on, every phase is timed end to end and runs whichever way has been",
                        "cheaper, re-checking the other path every few hundred ticks. Turn it off to",
                        "force the parallel path always, which is what earlier versions did.")
                .define("adaptive", true);
        IGNORE_MOD_CONFLICTS = builder
                .comment("Run the in-level phases even when a mod is installed whose optimisations assume one",
                        "thread per level. Lithium has shared mutable caches that can corrupt state, hang,",
                        "or crash under concurrent access. Skein checks the known conflicting options.",
                        "Leave this false; disable the reported options and restart instead of bypassing the checks.")
                .define("ignoreModConflicts", false);
        LOG_EVERY_TICKS = builder
                .comment("Log a one-line parallel-efficiency summary every N ticks. 0 disables it.")
                .defineInRange("logSummaryEveryTicks", 0, 0, 72000);
        builder.pop();

        builder.comment("Each ServerLevel ticks on its own worker, with a barrier before the server",
                        "thread moves on to connections and players. Dimensions share very little",
                        "mutable state, so this half needs nothing but the barrier.").push("dimensions");
        DIMENSIONS_ENABLED = builder.define("enabled", true);
        DIMENSION_BLACKLIST = builder
                .comment("Dimension IDs that must keep ticking on the server thread, e.g. \"minecraft:the_end\".",
                        "Blacklisted dimensions still tick every tick; they just do it serially.")
                .defineListAllowEmpty("blacklist", List.of(), () -> "", o -> o instanceof String);
        builder.pop();

        builder.comment("The geometry every in-level phase below is split by. Four half-offset grids",
                        "are laid over the world and each piece of work runs in the pass whose grid it",
                        "sits safely inside, so two things running at the same time are always at least",
                        "two margins apart and neither can reach what the other is writing.").push("regions");
        REGION_CHUNKS = builder
                .comment("Cell edge length in chunks. Smaller means more cells and more parallelism;",
                        "it is raised automatically if it is too small for the margin to work, and",
                        "rounded up to an even number so cells stay chunk-aligned in both grids.")
                .defineInRange("chunkSize", 8, 2, 64);
        REGION_MARGIN = builder
                .comment("How far inside its own cell, in blocks, something has to be before it may",
                        "run in parallel. This is the safety margin the whole design rests on, so it",
                        "wants to be at least as far as a tick reaches -- mob targeting and pathfinding,",
                        "a sapling turning into a tree, a hopper's neighbours. Raising it costs cell",
                        "size, not coverage.")
                .defineInRange("marginBlocks", 32, 8, 256);
        builder.pop();

        builder.comment("Entities inside one dimension tick across cells. Structural writes -- spawns,",
                        "removals, section moves, scheduled ticks -- are parked and applied by the",
                        "coordinator at each barrier rather than locked.").push("entities");
        ENTITIES_ENABLED = builder.define("enabled", true);
        THREADED_PROJECTILE_QUERIES = builder
                .comment("Use ordered target lists for vanilla snowball/egg queries during threaded ticks.",
                        "Other targets keep live vanilla collision checks. Modded subclasses use the normal query.",
                        "Disable if another mod makes ordinary vanilla snowballs or eggs hittable by projectiles.")
                .define("threadedProjectileQueries", true);
        COLLISION_INDEX = builder
                .comment("Answer entity-collision queries from a per-section list of the entities that can be",
                        "collided with at all (boats, shulkers, and modded entities that say so).",
                        "Without it, a piston pushing a stack of thousands of primed TNT asks every TNT about every",
                        "other one: the cost grows with the square of the stack. Same answers as vanilla; it steps",
                        "aside by itself if another mod changes Entity's collision rules.")
                .define("collisionIndex", true);
        PARALLEL_PISTON_PUSHES = builder
                .comment("Spread a piston push of a large stack of primed TNT or falling blocks (a cannon's payload)",
                        "over the pool. Each entity's move is its own, so they end exactly where vanilla puts them;",
                        "section moves and block reactions are replayed in vanilla's order at the end of the push.")
                .define("parallelPistonPushes", true);
        PARALLEL_EXPLOSION_RAYS = builder
                .comment("Cast each explosion's 1,352 block rays across the pool instead of one after another.",
                        "Explosions that affect each other still run in order -- a column of stacked TNT is one",
                        "chain on one thread -- but the rays inside one explosion only read the world, so they",
                        "can use the idle workers. The random draws are taken in vanilla's order first and the",
                        "hits go into the affected set in ray order, so the same blocks break in the same order.",
                        "Only vanilla damage calculators, from TNT, creepers or no source, are cast this way; the",
                        "rays call the calculator from several threads at once. Turn off if a mod's block",
                        "explosion resistance cannot be asked from more than one thread.")
                .define("parallelExplosionRays", true);
        THREADED_ENTITY_TRACKING = builder
                .comment("Work out each tracked entity's movement and data packets across the pool, once the",
                        "entity phase is over. The packets are collected rather than sent, and sent afterwards",
                        "on the level's thread in vanilla's order, to the same players. Players, item frames,",
                        "entities that changed chunk section and vehicles whose passengers changed are still",
                        "handled where vanilla handles them. Turn off if a mod adds to ServerEntity.sendChanges",
                        "something that cannot run off the level's thread.")
                .define("threadedEntityTracking", true);
        ENTITY_MIN_COUNT = builder
                .comment("Do not bother going parallel below this many ticking entities in a level.")
                .defineInRange("minEntities", 100, 0, 100000);
        ENTITY_PIN_PLAYERS = builder
                .comment("Tick players on the level-owning thread instead of in a cell.",
                        "A player tick reaches well past the player -- advancements, stats, the open",
                        "container, the game-mode state machine -- and none of that is bounded by the",
                        "margin. There are never enough players for this to cost anything.")
                .define("pinPlayers", true);
        ENTITY_LOADED_MARGIN = builder
                .comment("Entities within this many chunks of a chunk that is not fully loaded tick on",
                        "the level-owning thread. A mob at the edge of the loaded area reads blocks",
                        "beyond it -- pathfinding looks follow range + 8 blocks out -- and in vanilla",
                        "that read loads the chunk on the spot. A parallel tick must never do that:",
                        "the chunk system's trackers are single-threaded and a load from a worker",
                        "corrupts them. So a worker's read of an unloaded chunk fails and the entity",
                        "loses that tick, and this margin keeps the entities that would ask on the",
                        "thread where the load is legal. 0 disables the check.")
                .defineInRange("loadedMarginChunks", 3, 0, 8);
        ENTITY_BLACKLIST = builder
                .comment("Entity type IDs that always tick serially, e.g. \"minecraft:ender_dragon\".")
                .defineListAllowEmpty("blacklist", List.of("minecraft:ender_dragon"), () -> "", o -> o instanceof String);

        builder.comment("A cell is a conservative conflict test: it assumes any two entities in it may",
                        "reach each other, so a few thousand entities in one cell tick one after the",
                        "other however many threads are free. The cluster tier replaces that test with",
                        "the real one for entities that provably reach only a block or two, and orders",
                        "them by precedence instead of by position -- so a pile that shares a single",
                        "cell, or a single bubble column, still spreads across the pool.").push("cluster");
        ENTITY_CLUSTER_ENABLED = builder.define("enabled", true);
        ENTITY_CLUSTER_REACH = builder
                .comment("How far a clustered entity's tick may reach, in blocks, including how far it",
                        "moves in that tick. Two clustered entities further apart than this never",
                        "interact, so they may run at the same time whatever their order in the list.",
                        "This is the margin of the cluster tier and the whole safety argument rests on",
                        "it: raise it if a modded type in the list reaches further, and it costs speed",
                        "rather than correctness. Lower it and it costs correctness.")
                .defineInRange("reachBlocks", 6.0, 1.0, 64.0);
        ENTITY_CLUSTER_ISOLATION = builder
                .comment("How far a clustered entity must be from any NON-clustered entity to stay in",
                        "this tier, in blocks. A clustered entity may not reach another clustered one,",
                        "but it can certainly reach a mob -- a snowball hits it -- and two snowballs",
                        "that cannot touch each other can still hit the SAME mob at the same instant,",
                        "which races that mob's own state. Anything with a mob, a player or any other",
                        "gridded entity this close is handed back to the grid, where the margin keeps",
                        "the pair on one thread. In a farm with nothing else in it this excludes",
                        "nobody and costs one spatial query per entity.",
                        "It must exceed how far any clustered type can WRITE to a non-clustered one:",
                        "about 4 for a projectile (its hit box inflated by 1, plus a tick of movement)",
                        "and 8 for an experience orb, which reaches that far to a player. The default",
                        "is the larger of those. Raising it costs speed near mobs, not correctness;",
                        "lowering it below what a type reaches costs correctness.",
                        "Vanilla snowballs and eggs use exactProjectileIsolation instead while it is on.")
                .defineInRange("isolationBlocks", 8.0, 1.0, 128.0);
        ENTITY_CLUSTER_EXACT_PROJECTILES = builder
                .comment("Test vanilla snowballs and eggs against what they can actually hit this tick,",
                        "rather than against isolationBlocks. The only thing such a projectile writes",
                        "outside this tier is the entity it hits, and vanilla hits only an entity whose",
                        "hitbox, grown by 0.3, crosses the projectile's path for this tick. So a",
                        "snowball stays clustered unless some gridded entity's hitbox touches that path",
                        "plus a quarter-block allowance. Without this, a player standing beside a",
                        "bubble column is within isolationBlocks of every snowball in it, and the",
                        "whole column falls back to one grid cell and one thread.",
                        "Exact vanilla classes only; modded subclasses keep isolationBlocks. Turn this",
                        "off if another mod lets vanilla snowballs or eggs affect entities they do not",
                        "hit, such as splash damage.")
                .define("exactProjectileIsolation", true);
        ENTITY_CLUSTER_MIN = builder
                .comment("Do not bother building the conflict graph below this many clustered entities.")
                .defineInRange("minEntities", 256, 0, 100000);
        ENTITY_CLUSTER_INERT = builder
                .comment("Blocks whose entityInside provably writes NOTHING, and so does not need to be",
                        "parked at the barrier when a clustered entity stands in one.",
                        "Skein finds hazardous blocks by asking which class overrides entityInside, which",
                        "catches every block that reacts to an entity at all -- including ones that only",
                        "push it. A bubble column is the important case: it reads one block state, pushes",
                        "the entity and spawns particles, and parking that serialises the hottest",
                        "operation in a projectile farm onto the server thread.",
                        "Only put a block here if you have read its entityInside and it cannot reach a",
                        "block write on ANY path -- note that a pressure plate looks inert for as long as",
                        "it stays pressed, so watching one is not evidence. Getting this wrong is a data",
                        "race, not a slowdown. Skein logs candidates it has never seen write.")
                .defineListAllowEmpty("inertBlocks", List.of("minecraft:bubble_column"),
                        () -> "", o -> o instanceof String);
        ENTITY_CLUSTER_TYPES = builder
                .comment("Entity type IDs eligible for the cluster tier, each optionally followed by",
                        "\"=<blocks>\": how far that type reaches toward ANOTHER CLUSTERED entity.",
                        "Not how far it moves, and not how far it can see -- how far it can write.",
                        "A thrown projectile is 0 because it can only hit what is pickable and nothing",
                        "in this tier is; that zero is what lets a dense farm spread across the pool.",
                        "Items and experience orbs are 2 because they merge with their own kind.",
                        "Omitting \"=<blocks>\" uses reachBlocks, which is the safe answer, not the",
                        "fast one. A type belongs here at all only if its whole tick writes no blocks",
                        "and reaches nothing outside this list.")
                .defineListAllowEmpty("types", CLUSTERABLE_ENTITIES, () -> "", o -> o instanceof String);
        builder.pop();
        builder.pop();

        builder.comment("Random block and fluid ticks -- crop growth, grass spread, fire, ice and snow,",
                        "leaf decay -- run across cells. A random tick reaches a few blocks at most,",
                        "which makes this the safest of the in-level phases; the widest reach in vanilla",
                        "is a sapling becoming a large tree.",
                        "Mob spawning, which vanilla interleaves with this in the same loop, still runs",
                        "on the level-owning thread: its caps are a running total over the whole level.")
                .push("randomTicks");
        RANDOM_TICKS_ENABLED = builder.define("enabled", true);
        RANDOM_TICK_MIN_CHUNKS = builder
                .comment("Do not bother going parallel below this many randomly-ticking chunks in a level.")
                .defineInRange("minChunks", 16, 0, 100000);
        PARALLEL_CHUNK_SCAN = builder
                .comment("Look at every loaded chunk's holder across the pool, twice a tick: once for the chunk",
                        "the loop will tick, once for whether it has changes to send. Each look is a few reads",
                        "that nearly always miss the CPU cache, so with a large view distance the two walks",
                        "cost about as much as everything else in the chunk tick. The loop, the spawner and",
                        "the broadcasts still run on the level-owning thread, in vanilla's order.")
                .define("parallelChunkScan", true);
        builder.pop();

        builder.comment("Scheduled block and fluid ticks -- redstone, fluid flow, falling blocks,",
                        "crops on a timer -- run across connected components rather than across the",
                        "grid the other phases use. A scheduled tick carries a sub-tick number that",
                        "orders it against every other tick this game tick, and redstone depends on",
                        "that order, so nothing that could interact may be reordered: work is grouped",
                        "by proximity instead, and only groups further apart than anything can reach",
                        "run at the same time.",
                        "Parallelism therefore depends on how spread out the world's redstone and",
                        "fluids are. One spreading lake is one component and runs exactly as vanilla",
                        "runs it; forty separate farms are forty components.",
                        "What groups share -- block events, new entities, new ticks' sub-tick numbers --",
                        "is put back in vanilla's order, so the result matches vanilla tick for tick:",
                        "every block, entity and pending tick, in order (docs/redstone-parity-2026-10-11.md).",
                        "Like the other phases it is timed both ways and runs vanilla's loop when that is",
                        "cheaper. A stalled phase logs every worker's stack trace after ten seconds.")
                .push("scheduledTicks");
        SCHEDULED_TICKS_ENABLED = builder.define("enabled", true);
        SCHEDULED_TICK_MIN_COUNT = builder
                .comment("Do not bother going parallel below this many ticks collected for one tick.")
                .defineInRange("minTicks", 64, 0, 1000000);
        SCHEDULED_TICK_BLACKLIST = builder
                .comment("Block or fluid IDs whose scheduled tick is not bounded by the margin.",
                        "One of these anywhere in a tick's collected set sends that whole set back",
                        "through vanilla's serial loop, which is the only honest thing to do with a",
                        "tick that can touch anything. A repeating command block reschedules itself",
                        "every tick, so one of those keeps this phase off for its dimension.")
                .defineListAllowEmpty("blacklist", UNBOUNDED_SCHEDULED_TICKS, () -> "", o -> o instanceof String);
        builder.pop();

        builder.comment("Block entities tick across cells. Usually the largest single cost on a server",
                        "that has been played on, and a good fit for the split: a hopper, furnace or",
                        "spawner is at a fixed position and reaches a block or two.",
                        "This is also the phase most exposed to other mods -- a machine that reaches",
                        "across the world is a genre, not an oddity -- so it is the first switch to try",
                        "if a tech modpack misbehaves.")
                .push("blockEntities");
        BLOCK_ENTITIES_ENABLED = builder.define("enabled", true);
        BLOCK_ENTITY_MIN_COUNT = builder
                .comment("Do not bother going parallel below this many ticking block entities in a level.")
                .defineInRange("minTickers", 64, 0, 100000);
        BLOCK_ENTITY_ORDER_LIMIT = builder
                .comment("Above 0, block entities close enough to affect each other tick in vanilla's order, in",
                        "groups that run side by side: a hopper and the hoppers it feeds, a piston and what it",
                        "moves. A group bigger than this is split by the grid instead. 0, the default, uses the",
                        "grid everywhere, which can tick two neighbours in the other order but keeps dense",
                        "builds parallel: a TNT cannon's moving pistons are all within reach of each other, so in",
                        "vanilla's order they tick as one group, on one thread. 512 orders all but the densest.")
                .defineInRange("vanillaOrderLimit", 0, 0, 1000000);
        BLOCK_ENTITY_BLACKLIST = builder
                .comment("Block entity type IDs that always tick serially. The defaults are the vanilla",
                        "types whose tick reaches further than any sane margin: beacons and conduits",
                        "apply effects to players tens of blocks away, bells make raiders glow at 48,",
                        "and end gateways teleport across dimensions.")
                .defineListAllowEmpty("blacklist", LONG_REACH_BLOCK_ENTITIES, () -> "", o -> o instanceof String);
        builder.pop();

        builder.comment("The periodic autosave. Every autosave interval the server walks every loaded",
                        "chunk and serialises the dirty ones -- NBT for 24 sections, every block entity,",
                        "and the light data -- with no time budget at all, on the server thread. It is",
                        "the largest single stall a vanilla server has: on a big world it is hundreds of",
                        "milliseconds to seconds, which is ten to sixty dropped ticks.",
                        "Chunks are independent and nothing is ticking while this runs, so it splits",
                        "cleanly. What it costs is that ChunkDataEvent.Save fires on a worker, so any",
                        "mod listening to it must tolerate that -- which is why this is off until you",
                        "have soaked it with your own mod list.").push("saving");
        SAVING_ENABLED = builder.define("enabled", false);
        SAVING_MIN_CHUNKS = builder
                .comment("Do not bother going parallel below this many loaded chunks in a level.")
                .defineInRange("minChunks", 256, 0, 1000000);
        builder.pop();

        SPEC = builder.build();
    }

    /** The snapshot the mixins and schedulers actually read. */
    public static final class Live {
        public static volatile boolean enabled = true;
        public static volatile boolean dimensions = true;
        public static volatile boolean entities = true;
        public static volatile boolean threadedProjectileQueries = true;
        public static volatile boolean collisionIndex = true;
        public static volatile boolean parallelPistonPushes = true;
        public static volatile boolean parallelExplosionRays = true;
        public static volatile boolean threadedEntityTracking = true;
        public static volatile boolean randomTicks = true;
        public static volatile boolean parallelChunkScan = true;
        public static volatile boolean blockEntities = true;
        public static volatile boolean parallelSaving = false;
        public static volatile int minSaveChunks = 256;
        public static volatile boolean scheduledTicks = true;
        public static volatile int minScheduledTicks = 64;
        /**
         * Whether the explosion hooks are running at all -- the parallel rays
         * and exposure, and the serial speed-ups around them. Not a setting:
         * {@code enabled} with every explosion hook in place; see {@link Hooks}.
         */
        public static volatile boolean explosionHooks = true;
        /** The same for the entity-tracking hooks, including the per-tick chunk answer cache. */
        public static volatile boolean trackingHooks = true;
        public static volatile int regionChunks = 8;
        public static volatile int borderMargin = 32;
        public static volatile Grid grid = new Grid(128, 32);
        public static volatile int minEntities = 100;
        public static volatile int minRandomTickChunks = 16;
        public static volatile int minBlockEntities = 64;
        public static volatile int blockEntityOrderLimit = 0;
        public static volatile boolean adaptive = true;
        public static volatile boolean pinPlayers = true;
        public static volatile int loadedMarginChunks = 3;
        public static volatile boolean clusterEntities = true;
        public static volatile double clusterReach = 6.0;
        public static volatile int minClusterEntities = 256;
        public static volatile double clusterIsolation = 8.0;
        public static volatile boolean exactProjectileIsolation = true;
        /** Resolved from ids by {@link #resolveRegistries()}; see {@link #entityBlacklist}. */
        public static final Map<EntityType<?>, Double> clusterTypes = new ConcurrentHashMap<>();
        public static final Set<String> clusterTypeIds = ConcurrentHashMap.newKeySet();

        /**
         * Blocks that write something when an entity is inside them or a
         * projectile hits them.
         *
         * <p>The cluster tier admits types by what <em>they</em> reach, but what
         * an entity's tick reaches is also decided by the block it happens to be
         * standing in. A wooden pressure plate's {@code entityInside} has
         * sensitivity EVERYTHING, so a dropped item sets a block, updates its
         * neighbours and schedules a tick; a target block's
         * {@code onProjectileHit} awards a statistic to a shooter who may be in
         * another chunk. Two clustered entities have no separation at all, so
         * both of those are unsynchronised writes.
         *
         * <p>Rather than test the block per entity, the set is resolved once and
         * the write is parked at the call site, where the state is already in
         * hand. Detected by asking which class declares the override, so it
         * covers modded blocks without a list.
         */
        public static final Set<Block> hazardBlocks = ConcurrentHashMap.newKeySet();

        /**
         * Blocks that act on an entity standing or landing on them
         * ({@code stepOn}, {@code fallOn}), which a clustered item or orb
         * does too: redstone ore lights up under a dropped item, a sculk
         * sensor takes the step as a vibration. Kept apart from
         * {@link #hazardBlocks} so that a block overriding only one of these
         * is parked only there.
         */
        public static final Set<Block> landingHazards = ConcurrentHashMap.newKeySet();

        /**
         * Blocks in {@link #hazardBlocks} that also override
         * {@code updateEntityAfterFallOn}, the hook {@code Entity.move} calls
         * for the block under an entity that has just landed. A Create belt
         * calls its own {@code entityInside} from there, which puts the item
         * into the belt's inventory -- the same write {@link #hazardBlocks}
         * parks, reached by a path {@code checkInsideBlocks} never sees. A
         * block that overrides it only to change the bounce (slime, beds) is
         * not in {@link #hazardBlocks} and so is not parked.
         */
        public static final Set<Block> afterFallHazards = ConcurrentHashMap.newKeySet();
        public static final Set<String> inertBlockIds = ConcurrentHashMap.newKeySet();

        /**
         * How far this type reaches toward <em>another clustered</em> entity.
         *
         * <p>Nearly always zero, and that is the whole point. A thrown
         * projectile only hits what {@code Entity.canBeHitByProjectile} admits,
         * which is {@code isPickable} -- and {@code Projectile} overrides that
         * to {@code getType().is(REDIRECTABLE_PROJECTILE)}, a tag holding only
         * fireballs and wind charges. So two snowballs a block apart cannot
         * touch each other and may tick at the same time however crowded they
         * are. Note that this is a fact about the tag's <em>contents</em>, not
         * about the class: a datapack that adds a clustered type to that tag
         * would make it deflect its own kind, which {@link #resolveRegistries}
         * refuses. Items and experience orbs are the standing exception: they
         * merge with their own kind inside {@code inflate(0.5)} and write to
         * each other doing it.
         */
        public static double clusterRadius(EntityType<?> type) {
            Double radius = clusterTypes.get(type);
            return radius == null ? 0.0 : radius;
        }
        public static volatile int threadCount = 0;
        public static volatile boolean useAllCpuThreads = false;
        public static volatile CrashPolicy crashPolicy = CrashPolicy.DISABLE;
        public static volatile boolean threadSafeRandom = true;
        public static volatile boolean ignoreModConflicts = false;
        public static volatile int logEveryTicks = 0;
        public static final Set<String> dimensionBlacklist = ConcurrentHashMap.newKeySet();
        /**
         * Resolved entity types, not ids. {@code isPinned} runs once per entity
         * per tick on the coordinator with every worker parked, so it must not
         * build a {@code ResourceLocation} string to answer a set lookup.
         */
        public static final Set<EntityType<?>> entityBlacklist = ConcurrentHashMap.newKeySet();
        public static final Set<String> entityBlacklistIds = ConcurrentHashMap.newKeySet();
        /**
         * Resolved from ids by {@link #resolveRegistries()} rather than by
         * {@link #refresh()}: configs load before mod registries are populated,
         * so a modded id would silently never match.
         */
        public static final Set<BlockEntityType<?>> blockEntityBlacklist = ConcurrentHashMap.newKeySet();
        public static final Set<String> blockEntityBlacklistIds = ConcurrentHashMap.newKeySet();
        /** Resolved {@code Block} and {@code Fluid} instances; see {@link #resolveRegistries()}. */
        public static final Set<Object> scheduledTickBlacklist = ConcurrentHashMap.newKeySet();
        public static final Set<String> scheduledTickBlacklistIds = ConcurrentHashMap.newKeySet();

        private Live() {}
    }

    /**
     * Set when the config has changed on disk and the snapshot has not caught
     * up yet.
     *
     * <p>FML fires its reload event straight from NightConfig's file-watcher
     * thread, so a snapshot taken there rebuilds {@link Live} underneath a tick
     * that is reading it. Most of these are single volatile stores and survive
     * that, but the blacklists are cleared and refilled in place, and both of
     * them are safety gates rather than preferences -- a dimension pulled out of
     * the parallel set, an entity whose reach the margin does not cover. Between
     * the clear and the refill they read as empty, which is the one answer that
     * must never be given.
     */
    private static volatile boolean stale;

    /** Records a config change for the server thread to pick up. */
    public static void markStale() {
        stale = true;
    }

    /** Applies a config change if one has arrived. Server thread, between ticks. */
    public static void refreshIfStale() {
        if (stale) {
            refresh();
            // An edit to either reach blacklist is only half applied until the
            // ids in it are turned back into registry objects, which is what
            // the tickers match against. Without this a blacklist added to on
            // disk keeps ticking in parallel while the log says the config
            // reloaded -- the one direction of staleness that is not safe.
            List<String> unknown = resolveRegistries();
            if (!unknown.isEmpty()) {
                Skein.LOGGER.warn("Skein: blacklist names {} which nothing is registered under; ignoring", unknown);
            }
        }
    }

    public static void refresh() {
        // Cleared first, so a change that lands while this is running is picked
        // up next tick rather than lost.
        stale = false;
        // A feature whose hooks did not all make it into the game is off,
        // whatever the file says; see Hooks.
        Live.enabled = ENABLED.get() && Hooks.allows(Hooks.Feature.CORE);
        Live.dimensions = DIMENSIONS_ENABLED.get() && Hooks.allows(Hooks.Feature.DIMENSIONS);
        Live.entities = ENTITIES_ENABLED.get() && Hooks.allows(Hooks.Feature.ENTITIES);
        Live.threadedProjectileQueries = THREADED_PROJECTILE_QUERIES.get()
                && Hooks.allows(Hooks.Feature.PROJECTILE_QUERIES);
        Live.collisionIndex = COLLISION_INDEX.get() && Hooks.allows(Hooks.Feature.COLLISION_QUERIES);
        Live.parallelPistonPushes = PARALLEL_PISTON_PUSHES.get() && Hooks.allows(Hooks.Feature.PISTON_PUSHES);
        Live.explosionHooks = Live.enabled && Hooks.allows(Hooks.Feature.EXPLOSIONS);
        Live.parallelExplosionRays = PARALLEL_EXPLOSION_RAYS.get() && Live.explosionHooks;
        Live.trackingHooks = Live.enabled && Hooks.allows(Hooks.Feature.ENTITY_TRACKING);
        Live.threadedEntityTracking = THREADED_ENTITY_TRACKING.get() && Live.trackingHooks;
        Live.randomTicks = RANDOM_TICKS_ENABLED.get() && Hooks.allows(Hooks.Feature.RANDOM_TICKS);
        Live.parallelChunkScan = PARALLEL_CHUNK_SCAN.get() && Hooks.allows(Hooks.Feature.CHUNK_SCAN);
        Live.blockEntities = BLOCK_ENTITIES_ENABLED.get() && Hooks.allows(Hooks.Feature.BLOCK_ENTITIES);
        Live.scheduledTicks = SCHEDULED_TICKS_ENABLED.get() && Hooks.allows(Hooks.Feature.SCHEDULED_TICKS);
        Live.minScheduledTicks = SCHEDULED_TICK_MIN_COUNT.get();
        Live.borderMargin = REGION_MARGIN.get();
        Live.regionChunks = resolveRegionChunks(REGION_CHUNKS.get(), Live.borderMargin);
        Live.grid = new Grid(Live.regionChunks * 16, Live.borderMargin);
        Live.adaptive = ADAPTIVE.get();
        Live.minEntities = ENTITY_MIN_COUNT.get();
        Live.clusterEntities = ENTITY_CLUSTER_ENABLED.get();
        Live.clusterReach = ENTITY_CLUSTER_REACH.get();
        Live.minClusterEntities = ENTITY_CLUSTER_MIN.get();
        Live.parallelSaving = SAVING_ENABLED.get() && Hooks.allows(Hooks.Feature.PARALLEL_SAVING);
        Live.minSaveChunks = SAVING_MIN_CHUNKS.get();
        Live.clusterIsolation = ENTITY_CLUSTER_ISOLATION.get();
        Live.exactProjectileIsolation = ENTITY_CLUSTER_EXACT_PROJECTILES.get();
        Live.minRandomTickChunks = RANDOM_TICK_MIN_CHUNKS.get();
        Live.minBlockEntities = BLOCK_ENTITY_MIN_COUNT.get();
        Live.blockEntityOrderLimit = BLOCK_ENTITY_ORDER_LIMIT.get();
        Live.pinPlayers = ENTITY_PIN_PLAYERS.get();
        Live.loadedMarginChunks = ENTITY_LOADED_MARGIN.get();
        Live.threadCount = THREAD_COUNT.get();
        Live.useAllCpuThreads = USE_ALL_CPU_THREADS.get();
        Live.crashPolicy = CRASH_POLICY.get();
        Live.threadSafeRandom = THREAD_SAFE_RANDOM.get();
        Live.ignoreModConflicts = IGNORE_MOD_CONFLICTS.get();
        Live.logEveryTicks = LOG_EVERY_TICKS.get();

        Live.dimensionBlacklist.clear();
        DIMENSION_BLACKLIST.get().forEach(s -> Live.dimensionBlacklist.add(s.toString()));
        Live.inertBlockIds.clear();
        ENTITY_CLUSTER_INERT.get().forEach(s -> Live.inertBlockIds.add(s.toString()));
        Live.clusterTypeIds.clear();
        ENTITY_CLUSTER_TYPES.get().forEach(s -> Live.clusterTypeIds.add(s.toString()));
        Live.entityBlacklistIds.clear();
        ENTITY_BLACKLIST.get().forEach(s -> Live.entityBlacklistIds.add(s.toString()));
        Live.blockEntityBlacklistIds.clear();
        BLOCK_ENTITY_BLACKLIST.get().forEach(s -> Live.blockEntityBlacklistIds.add(s.toString()));
        Live.scheduledTickBlacklistIds.clear();
        SCHEDULED_TICK_BLACKLIST.get().forEach(s -> Live.scheduledTickBlacklistIds.add(s.toString()));
    }

    private static boolean redirectable(EntityType<?> type) {
        try {
            return type.is(net.minecraft.tags.EntityTypeTags.REDIRECTABLE_PROJECTILE);
        } catch (Throwable tagsNotBound) {
            // Tags load with the datapacks; if they are not bound yet, assume
            // the dangerous answer rather than the fast one.
            return true;
        }
    }

    /**
     * Whether {@code type} declares its own {@code name}, rather than inheriting
     * {@link BlockBehaviour}'s empty one. Walks up from the concrete class, so
     * the first declaration found is the effective override.
     */
    private static final Set<String> UNRESOLVED_OVERRIDES = ConcurrentHashMap.newKeySet();
    /** Block classes whose methods cannot be listed on this server; see {@link #overrides}. */
    private static final Set<Class<?>> UNLINKABLE = ConcurrentHashMap.newKeySet();

    private static boolean overrides(Class<?> type, String name, Class<?>... params) {
        return overrides(type, BlockBehaviour.class, name, params);
    }

    /** As above, for a method whose empty default is declared on {@code base}. */
    private static boolean overrides(Class<?> type, Class<?> base, String name, Class<?>... params) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (UNLINKABLE.contains(c)) {
                return true;
            }
            try {
                c.getDeclaredMethod(name, params);
                return c != base;
            } catch (NoSuchMethodException keepWalking) {
                // declared further up
            } catch (LinkageError | TypeNotPresentException unlinkable) {
                // Looking a method up resolves every type in the class's
                // method signatures, and a mod's block can declare one that
                // names a client-only class, which a dedicated server cannot
                // load. Nothing can be learned about such a class, so it is
                // treated as one that reacts: the safe answer, and the slow one.
                // Asked once: each attempt makes the class loader log errors.
                if (UNLINKABLE.add(c)) {
                    Skein.LOGGER.info("Skein: {} declares a method naming a class this server cannot load ({}). "
                            + "Any class-loading errors just above come from Skein looking, and are harmless; its "
                            + "blocks are treated as ones that react to entities.", c.getName(), unlinkable.toString());
                }
                return true;
            }
        }
        // Only if the method could not be found at all -- a signature change.
        // Treat everything as hazardous rather than silently stop parking, and
        // say so: every block contact in the cluster tier then waits for the
        // barrier, which is safe and slow.
        if (UNRESOLVED_OVERRIDES.add(name)) {
            Skein.LOGGER.warn("Skein: could not find {} on {} or any superclass; treating every block as one "
                    + "that reacts to it", name, type.getName());
        }
        return true;
    }

    /**
     * Turns the id-shaped parts of the config into registry objects. Called once
     * the server exists, because that is the first point at which every mod's
     * block entity types are actually registered.
     *
     * @return ids that matched nothing, for logging
     */
    public static List<String> resolveRegistries() {
        Live.blockEntityBlacklist.clear();
        List<String> unknown = new java.util.ArrayList<>();
        for (String id : Live.blockEntityBlacklistIds) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            BlockEntityType<?> type = location == null ? null : BuiltInRegistries.BLOCK_ENTITY_TYPE.get(location);
            if (type == null) {
                unknown.add(id);
            } else {
                Live.blockEntityBlacklist.add(type);
            }
        }
        // Other mods' block entities that reach past any margin, when installed.
        for (String id : com.theproknightgamer.skein.core.CreateCompat.LONG_REACH) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location != null && BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(location)) {
                Live.blockEntityBlacklist.add(BuiltInRegistries.BLOCK_ENTITY_TYPE.get(location));
            }
        }

        Live.entityBlacklist.clear();
        for (String id : Live.entityBlacklistIds) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            EntityType<?> type = location == null ? null : BuiltInRegistries.ENTITY_TYPE.get(location);
            // ENTITY_TYPE returns PIG rather than null for an unknown id, so an
            // id that is not registered has to be caught by the key check.
            if (location == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(location) || type == null) {
                unknown.add(id);
            } else {
                Live.entityBlacklist.add(type);
            }
        }

        Live.hazardBlocks.clear();
        Live.landingHazards.clear();
        Live.afterFallHazards.clear();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (overrides(block.getClass(), "entityInside",
                          BlockState.class, Level.class, BlockPos.class, Entity.class)
                    || overrides(block.getClass(), "onProjectileHit",
                                 Level.class, BlockState.class, BlockHitResult.class, Projectile.class)) {
                Live.hazardBlocks.add(block);
            }
            if (overrides(block.getClass(), Block.class, "stepOn",
                          Level.class, BlockPos.class, BlockState.class, Entity.class)
                    || overrides(block.getClass(), Block.class, "fallOn",
                                 Level.class, BlockState.class, BlockPos.class, Entity.class, float.class)) {
                Live.landingHazards.add(block);
            }
            if (Live.hazardBlocks.contains(block) && overrides(block.getClass(), Block.class,
                    "updateEntityAfterFallOn", BlockGetter.class, Entity.class)) {
                Live.afterFallHazards.add(block);
            }
        }

        for (String id : Live.inertBlockIds) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location == null || !BuiltInRegistries.BLOCK.containsKey(location)) {
                unknown.add(id);
            } else {
                Block inert = BuiltInRegistries.BLOCK.get(location);
                Live.hazardBlocks.remove(inert);
                Live.landingHazards.remove(inert);
                Live.afterFallHazards.remove(inert);
            }
        }
        HazardWatch.reset();

        Live.clusterTypes.clear();
        for (String entry : Live.clusterTypeIds) {
            int equals = entry.indexOf('=');
            String id = equals < 0 ? entry : entry.substring(0, equals);
            double radius = Live.clusterReach;
            if (equals >= 0) {
                try {
                    radius = Double.parseDouble(entry.substring(equals + 1).trim());
                } catch (NumberFormatException malformed) {
                    unknown.add(entry);
                    continue;
                }
            }
            ResourceLocation location = ResourceLocation.tryParse(id.trim());
            if (location == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(location)) {
                unknown.add(entry);
            } else {
                // Not clamped to clusterReach: that value is documented as the
                // default for a type that gives no "=<blocks>", and capping an
                // explicit radius with it turns lowering the default into a
                // silent correctness change.
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(location);
                double resolved = Math.max(0.0, radius);
                if (resolved <= 0.0 && redirectable(type)) {
                    // Projectile.isPickable is this tag, so a type inside it can
                    // be hit -- and deflected -- by another projectile. A reach
                    // of zero would put every one of them on one level with no
                    // barrier anywhere between them.
                    Skein.LOGGER.warn("Skein: {} is in #minecraft:redirectable_projectile, so it can be hit "
                            + "by its own kind; raising its cluster reach from 0 to {}", id, Live.clusterReach);
                    resolved = Live.clusterReach;
                }
                Live.clusterTypes.put(type, resolved);
            }
        }

        Live.scheduledTickBlacklist.clear();
        for (String id : Live.scheduledTickBlacklistIds) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            boolean matched = false;
            if (location != null && BuiltInRegistries.BLOCK.containsKey(location)) {
                Block block = BuiltInRegistries.BLOCK.get(location);
                Live.scheduledTickBlacklist.add(block);
                matched = true;
            }
            if (location != null && BuiltInRegistries.FLUID.containsKey(location)) {
                Fluid fluid = BuiltInRegistries.FLUID.get(location);
                Live.scheduledTickBlacklist.add(fluid);
                matched = true;
            }
            if (!matched) {
                unknown.add(id);
            }
        }
        return unknown;
    }

    /**
     * The cell size actually used: at least four margins across so every
     * position is interior to one of the four grids, even so that both grids'
     * cell edges land on chunk boundaries, and wide enough that a chunk's whole
     * 16-block span fits too.
     *
     * <p>That last step is the one four margins does not buy. Between the two
     * offsets of an axis there is a stretch the unshifted grid has already
     * given up on and the shifted one has not yet taken over -- empty for a
     * position, but {@code WIDEST_FOOTPRINT} wide for a chunk, and a chunk
     * whose left edge lands in it is interior to neither grid and goes back to
     * the serial set, which is exactly what raising the margin is supposed not
     * to cost. So the cell grows until the first chunk-aligned position at or
     * past the start of that stretch is beyond its end. Only the left of the
     * two stretches has to be checked: an even cell puts the other exactly half
     * a cell away, which is a whole number of chunks.
     */
    public static int resolveRegionChunks(int configured, int margin) {
        int needed = -Math.floorDiv(-margin * MARGINS_PER_REGION, 16);
        int chunks = Math.max(configured, needed);
        chunks += chunks & 1;
        while (16 * (Math.floorDiv(chunks * 8 - margin - WIDEST_FOOTPRINT, 16) + 1) < margin) {
            chunks += 2;
        }
        return chunks;
    }

    public static int resolveThreadCount() {
        if (Live.useAllCpuThreads) {
            return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        }
        int configured = Live.threadCount;
        if (configured > 0) {
            return configured;
        }
        return Math.max(1, Math.min(16, Runtime.getRuntime().availableProcessors() - 1));
    }
}
