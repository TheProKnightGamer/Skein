package com.theproknightgamer.skein.compat;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.BulkRandom;
import com.theproknightgamer.skein.core.DeferredBuffer;
import com.theproknightgamer.skein.core.ExplosionRays;
import com.theproknightgamer.skein.core.SkeinRandom;
import com.theproknightgamer.skein.core.TickContext;
import com.theproknightgamer.skein.compat.mixin.ServerExplosionInvoker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Explosion rays cast on the pool against the same rays cast in vanilla's
 * loop: the affected blocks in the same order, the random stream left in the
 * same place, and the same damage and knockback -- from the server thread
 * outside a pass and from a worker inside one.
 */
final class ExplosionRaysVerification {
    private ExplosionRaysVerification() {}

    private static final int X = -3072;
    private static final int TRIALS = 48;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private record Result(List<BlockPos> blocks, long nextRandom, float damage, int hurts, Vec3 motion,
                          float damage2, int hurts2, Vec3 motion2) {}

    static void run(ServerLevel level) {
        boolean enabled = SkeinConfig.Live.enabled;
        boolean parallel = SkeinConfig.Live.parallelExplosionRays;
        TickContext.State context = TickContext.enter(level, false, null);
        try {
            BlockState[] states = { Blocks.AIR.defaultBlockState(), Blocks.GLASS.defaultBlockState(),
                    Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.OBSIDIAN.defaultBlockState(),
                    Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true),
                    Blocks.DIRT.defaultBlockState(), Blocks.BEDROCK.defaultBlockState() };
            // Spans a chunk corner, so rays cross into four chunks.
            for (int x = -9; x <= 9; x++) for (int y = -9; y <= 9; y++) for (int z = -9; z <= 9; z++) {
                level.setBlock(new BlockPos(X + x, 70 + y, z),
                        states[Math.floorMod(x * 31 + y * 17 + z * 7 + x * y, states.length)], 2);
            }
            settle(level);
            int compared = 0;
            int stale = 0;
            int firstHurt = 0;
            int secondHurt = 0;
            long castsBefore = ExplosionRays.casts();
            for (int trial = 0; trial < TRIALS; trial++) {
                final int t = trial;
                boolean wall = trial % 4 == 0; // open sky, where one block changes the view
                Result serial = explode(level, trial, true, false, level.getRandom()::setSeed, wall);
                Result cast = explode(level, trial, true, true, level.getRandom()::setSeed, wall);
                check(serial.equals(cast), "pool-cast rays differ from the serial loop, trial " + trial);
                if (cast.hurts() > 0) firstHurt++;
                if (cast.hurts2() > 0) secondHurt++;
                Result vanilla = explode(level, trial, false, false, level.getRandom()::setSeed, wall);
                check(new HashSet<>(vanilla.blocks()).equals(new HashSet<>(cast.blocks()))
                                && vanilla.nextRandom() == cast.nextRandom() && vanilla.damage() == cast.damage()
                                && vanilla.hurts() == cast.hurts() && vanilla.motion().equals(cast.motion())
                                && vanilla.damage2() == cast.damage2() && vanilla.hurts2() == cast.hurts2()
                                && vanilla.motion2().equals(cast.motion2()),
                        "pool-cast rays or exposure differ from vanilla, trial " + trial);
                if (wall) {
                    Result open = explode(level, trial, false, false, level.getRandom()::setSeed, false);
                    if (!vanilla.motion().equals(open.motion()) || !vanilla.motion2().equals(open.motion2())
                            || vanilla.damage() != open.damage() || vanilla.damage2() != open.damage2()) {
                        stale++;
                    }
                }
                // Inside a pass, from a worker: draws come from the worker's own
                // source and helper tickets go to the task's buffer.
                Result[] inPass = new Result[2];
                DeferredBuffer buffer = new DeferredBuffer();
                Skein.pool().invokeAll(2, index -> {
                    if (index != 0) return;
                    TickContext.State previous = TickContext.enter(level, true, buffer);
                    try {
                        inPass[0] = explode(level, t, true, false, seed -> TickContext.random().setSeed(seed), false);
                        inPass[1] = explode(level, t, true, true, seed -> TickContext.random().setSeed(seed), false);
                    } finally {
                        TickContext.exit(previous);
                    }
                });
                buffer.drain(level);
                check(inPass[0].blocks().equals(inPass[1].blocks()) && inPass[0].nextRandom() == inPass[1].nextRandom(),
                        "pool-cast rays differ from the serial loop inside a pass, trial " + trial);
                compared += cast.blocks().size();
            }
            // Lithium's raycast leaves air out of the list, which vanilla keeps.
            check(compared > (ExplosionRays.loopHooked() ? 1000 : 500),
                    "fixture broke too few blocks to mean anything: " + compared);
            if (ExplosionRays.loopHooked()) {
                check(ExplosionRays.casts() - castsBefore == TRIALS * 2L, "not every parallel variant was cast on the pool");
            } else {
                // Another mod's raycast holds the loop; every variant used it, and still had to agree.
                check(ExplosionRays.casts() == castsBefore, "rays were cast on the pool without the loop hook");
                Skein.LOGGER.info("Explosion rays: another mod's raycast holds the ray loop, so none were cast on the pool");
            }
            check(stale >= 4, "a mid-explosion wall changed a probe's exposure in only " + stale
                    + " trials; the stale case went untested");
            check(firstHurt >= TRIALS / 4 && secondHurt >= TRIALS / 4, "the explosion probes were hurt in only "
                    + firstHurt + " and " + secondHurt + " trials; damage and knockback went untested");
            int broken = finalizeTrials(level);
            Skein.LOGGER.info("Explosion ray verification passed: {} explosions (no source, TNT, creeper; radius 1-6; "
                    + "sky, terrain, world bottom, chunk corners) matched the serial loop's ordered blocks, random "
                    + "stream, damage and knockback, on and off the pool, {} affected blocks; exposure refused after "
                    + "a mid-explosion block change in {} trials; {} finalizations broke {} blocks exactly as "
                    + "vanilla's, with the same drops, primed TNT and random stream", TRIALS * 3, compared, stale,
                    FINALIZE_TRIALS * 2, broken);
        } finally {
            TickContext.exit(context);
            SkeinConfig.Live.enabled = enabled;
            SkeinConfig.Live.parallelExplosionRays = parallel;
        }
    }

    private static Result explode(ServerLevel level, int trial, boolean enabled, boolean parallel, LongConsumer seed,
                                  boolean wall) {
        SkeinConfig.Live.enabled = enabled;
        SkeinConfig.Live.parallelExplosionRays = parallel;
        double y = trial % 4 == 0 ? 100 : trial % 4 == 1 ? level.getMinY() + .25 : 70.5;
        double x = X + (trial % 3 == 2 ? 0.0 : .5);
        double z = trial % 3 == 2 ? 0.0 : .5;
        // Entity additions inside a pass wait for the barrier, so only probe
        // knockback outside one; inside, blocks and the random stream suffice.
        // Two probes put exposure on the pool. On wall trials whichever is hurt
        // first drops stone between the centre and the other. 1.21.11 works
        // out an entity's exposure once, before hurting it, so the probe
        // hurt second sees the wall, and its precomputed answer is stale and
        // must be refused.
        boolean inPass = TickContext.deferring();
        Probe probe = inPass ? null : new Probe(level);
        Probe second = inPass ? null : new Probe(level);
        int wallY = (int) Math.floor(y);
        // Between the centre and the second probe, and between the centre and the first.
        BlockPos[] walls = { new BlockPos(X - 1, wallY, 0), new BlockPos(X + 1, wallY, 0), new BlockPos(X + 1, wallY, 1) };
        BlockState[] before = new BlockState[walls.length];
        for (int i = 0; i < walls.length; i++) before[i] = level.getBlockState(walls[i]);
        if (probe != null) {
            probe.setPos(X + 2.5, y, 1.5);
            second.setPos(X - 2.5, y, .5);
            if (wall) {
                probe.onHurt = () -> level.setBlock(walls[0], Blocks.STONE.defaultBlockState(), 2);
                second.onHurt = () -> {
                    level.setBlock(walls[1], Blocks.STONE.defaultBlockState(), 2);
                    level.setBlock(walls[2], Blocks.STONE.defaultBlockState(), 2);
                };
            }
            check(level.addFreshEntity(probe) && level.addFreshEntity(second), "explosion probe rejected");
        }
        Entity source = switch (trial % 3) {
            case 0 -> null;
            case 1 -> new PrimedTnt(level, x, y, z, null);
            default -> new Creeper(EntityTypes.CREEPER, level);
        };
        try {
            seed.accept(9127L + trial);
            ServerExplosion explosion = new ServerExplosion(level, source, null, null, new Vec3(x, y, z), 1 + trial % 6,
                    false, Explosion.BlockInteraction.DESTROY);
            // Rays and entities, no breaking: every variant sees the same terrain.
            ServerExplosionInvoker steps = (ServerExplosionInvoker) explosion;
            List<BlockPos> blocks = steps.skeinTest$calculateExplodedPositions();
            steps.skeinTest$hurtEntities(blocks);
            long next = TickContext.deferring() ? TickContext.random().nextLong() : level.getRandom().nextLong();
            return new Result(new ArrayList<>(blocks), next,
                    probe == null ? 0 : probe.damage, probe == null ? 0 : probe.hurts,
                    probe == null ? Vec3.ZERO : probe.getDeltaMovement(),
                    second == null ? 0 : second.damage, second == null ? 0 : second.hurts,
                    second == null ? Vec3.ZERO : second.getDeltaMovement());
        } finally {
            if (probe != null) {
                probe.discard();
                second.discard();
                for (int i = 0; i < walls.length; i++) level.setBlock(walls[i], before[i], 2);
            }
        }
    }

    private static final int FINAL_X = -4096;
    private static final int FINALIZE_TRIALS = 16;
    private static final int FINAL_REACH = 9;

    private record Finalized(List<BlockState> blocks, int broken, List<String> entities, long nextRandom) {}

    /** Which part of two finalizations differs, so a failure says what to look at. */
    private static String describeDifference(Finalized vanilla, Finalized skein) {
        StringBuilder out = new StringBuilder();
        if (vanilla.broken() != skein.broken()) out.append("broken ").append(vanilla.broken()).append(" vs ").append(skein.broken()).append("; ");
        for (int i = 0; i < Math.min(vanilla.blocks().size(), skein.blocks().size()); i++) {
            if (vanilla.blocks().get(i) != skein.blocks().get(i)) {
                out.append("first block difference at ").append(i).append(": ").append(vanilla.blocks().get(i))
                        .append(" vs ").append(skein.blocks().get(i)).append("; ");
                break;
            }
        }
        if (!vanilla.entities().equals(skein.entities())) {
            out.append("entities ").append(vanilla.entities().size()).append(" vs ").append(skein.entities().size());
            for (int i = 0; i < Math.min(vanilla.entities().size(), skein.entities().size()); i++) {
                if (!vanilla.entities().get(i).equals(skein.entities().get(i))) {
                    out.append(", first at ").append(i).append(": [").append(vanilla.entities().get(i)).append("] vs [")
                            .append(skein.entities().get(i)).append("]");
                    break;
                }
            }
            out.append("; ");
        }
        if (vanilla.nextRandom() != skein.nextRandom()) out.append("random stream ends differently; ");
        return out.length() == 0 ? "no field differs" : out.toString();
    }

    /**
     * {@code interactWithBlocks} with Skein's held chunks and one-source shuffle
     * against vanilla's, from the same affected list on the same terrain: the
     * blocks left, every entity it adds (drops, primed TNT) and where the
     * random stream ends up. Returns how many blocks the trials broke.
     */
    private static int finalizeTrials(ServerLevel level) {
        // Both sources an explosion draws from must take the bulk path, or
        // these trials would only compare vanilla with itself.
        check(BulkRandom.reproduces(), "bulk random draws failed their self-test");
        // SuperChunk gives each level a checked subclass, which BulkRandom
        // leaves to its own draws; the trials then compare that path instead.
        RandomSource own = ((SkeinRandom) level.getRandom()).current();
        check(own instanceof LegacyRandomSource, "the level's own source is not a LegacyRandomSource");
        if (own.getClass() != LegacyRandomSource.class) {
            Skein.LOGGER.info("Explosion finalize trials: the level's source is {}, so its draws are taken one "
                    + "at a time; workers still take the bulk path", own.getClass().getName());
        }
        Skein.pool().invokeAll(2, index -> {
            TickContext.State previous = TickContext.enter(level, true, new DeferredBuffer());
            try {
                check(TickContext.random().getClass() == LegacyRandomSource.class,
                        "a worker's source is not a LegacyRandomSource");
            } finally {
                TickContext.exit(previous);
            }
        });
        int broken = 0;
        int added = 0;
        for (int trial = 0; trial < FINALIZE_TRIALS; trial++) {
            final int t = trial;
            Finalized vanilla = finalizeOnce(level, trial, false, level.getRandom()::setSeed);
            Finalized skein = finalizeOnce(level, trial, true, level.getRandom()::setSeed);
            check(vanilla.equals(skein), "interactWithBlocks differs from vanilla, trial " + trial + ": "
                    + describeDifference(vanilla, skein));
            Finalized[] inPass = new Finalized[2];
            for (int variant = 0; variant < 2; variant++) {
                final boolean enabled = variant == 1;
                DeferredBuffer buffer = new DeferredBuffer();
                long[] next = new long[1];
                Skein.pool().invokeAll(2, index -> {
                    if (index != 0) return;
                    TickContext.State previous = TickContext.enter(level, true, buffer);
                    try {
                        inPass[enabled ? 1 : 0] = finalizeOnce(level, t, enabled, seed -> TickContext.random().setSeed(seed));
                        next[0] = TickContext.random().nextLong();
                    } finally {
                        TickContext.exit(previous);
                    }
                });
                buffer.drain(level);
                Finalized ran = inPass[enabled ? 1 : 0];
                inPass[enabled ? 1 : 0] = new Finalized(ran.blocks(), ran.broken(), entitiesAt(level), next[0]);
                clearEntities(level);
            }
            check(inPass[0].equals(inPass[1]), "interactWithBlocks differs from vanilla inside a pass, trial " + trial);
            broken += skein.broken();
            added += skein.entities().size();
        }
        check(broken > 500 && added > 100, "finalize trials broke " + broken + " blocks and added " + added
                + " entities, too few to mean anything");
        return broken;
    }

    private static Finalized finalizeOnce(ServerLevel level, int trial, boolean enabled, LongConsumer seed) {
        BlockState[] states = { Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
                Blocks.TNT.defaultBlockState(), Blocks.GLASS.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(),
                Blocks.WATER.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.OBSIDIAN.defaultBlockState() };
        // Offset by trial so each one sits differently against the chunk grid.
        int cx = FINAL_X + trial * 3;
        for (int x = -FINAL_REACH; x <= FINAL_REACH; x++) for (int y = -FINAL_REACH; y <= FINAL_REACH; y++)
            for (int z = -FINAL_REACH; z <= FINAL_REACH; z++) {
                level.setBlock(new BlockPos(cx + x, 70 + y, z),
                        states[Math.floorMod(x * 13 + y * 7 + z * 5 + trial + x * z, states.length)], 2 | 16);
            }
        settle(level);
        clearEntities(level);
        SkeinConfig.Live.enabled = false;
        SkeinConfig.Live.parallelExplosionRays = false;
        seed.accept(40_503L + trial);
        // The affected list is built by vanilla both times, so both
        // interactWithBlocks calls start from the same list in the same order.
        ServerExplosion explosion = new ServerExplosion(level, null, null, null, new Vec3(cx + .5, 70.5, .5),
                2 + trial % 4, false, Explosion.BlockInteraction.DESTROY);
        ServerExplosionInvoker steps = (ServerExplosionInvoker) explosion;
        List<BlockPos> affected = steps.skeinTest$calculateExplodedPositions();
        SkeinConfig.Live.enabled = enabled;
        steps.skeinTest$interactWithBlocks(affected);
        SkeinConfig.Live.enabled = false;
        List<BlockState> blocks = new ArrayList<>();
        int broken = 0;
        for (int x = -FINAL_REACH; x <= FINAL_REACH; x++) for (int y = -FINAL_REACH; y <= FINAL_REACH; y++)
            for (int z = -FINAL_REACH; z <= FINAL_REACH; z++) {
                BlockState state = level.getBlockState(new BlockPos(cx + x, 70 + y, z));
                blocks.add(state);
                if (state != states[Math.floorMod(x * 13 + y * 7 + z * 5 + trial + x * z, states.length)]) broken++;
            }
        long next = TickContext.deferring() ? 0 : level.getRandom().nextLong();
        List<String> entities = TickContext.deferring() ? List.of() : entitiesAt(level);
        if (!TickContext.deferring()) clearEntities(level);
        return new Finalized(blocks, broken, entities, next);
    }

    private static AABB finalizeArea() {
        return new AABB(FINAL_X - 40, 40, -40, FINAL_X + FINALIZE_TRIALS * 3 + 40, 110, 40);
    }

    /** Every entity finalization added, oldest first, as what a player could tell apart. */
    private static List<String> entitiesAt(ServerLevel level) {
        List<Entity> found = new ArrayList<>(level.getEntities((Entity) null, finalizeArea(), e -> true));
        found.sort(java.util.Comparator.comparingInt(Entity::getId));
        List<String> described = new ArrayList<>();
        for (Entity entity : found) {
            String extra = entity instanceof ItemEntity item ? item.getItem().toString()
                    : entity instanceof PrimedTnt tnt ? "fuse " + tnt.getFuse() : "";
            // 26.2 gives a dropped item its starting velocity from the item's own
            // random source, which every new entity seeds afresh, so two runs of
            // vanilla already disagree on it; 1.21.11 drew it from the level's.
            String motion = entity instanceof ItemEntity ? "" : " " + entity.getDeltaMovement();
            described.add(entity.getType() + " " + entity.position() + motion + " " + extra);
        }
        return described;
    }

    private static void clearEntities(ServerLevel level) {
        for (Entity entity : level.getEntities((Entity) null, finalizeArea(), e -> true)) {
            entity.discard();
        }
    }

    /** Not living, so its exposure can come from the pool; 1.21.11 makes TNT's hurt final. */
    /**
     * Runs the chunk tasks the terrain setup queued. A chunk that finishes
     * loading promotes itself to FULL through the level's task queue, and only
     * then do queries see entities added to it; loading the next chunk drains
     * the queue, so without this the last chunk loaded stays invisible.
     */
    static void settle(ServerLevel level) {
        if (TickContext.deferring()) return;
        while (level.getChunkSource().pollTask()) {
            // Run until nothing is left.
        }
    }

    private static final class Probe extends Snowball {
        float damage;
        int hurts;
        Runnable onHurt;
        Probe(ServerLevel level) { super(level, X, 100, 0, new ItemStack(Items.SNOWBALL)); setDeltaMovement(Vec3.ZERO); }
        @Override public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
            damage = amount;
            hurts++;
            if (onHurt != null) {
                onHurt.run();
                onHurt = null;
            }
            return false;
        }
    }
}
