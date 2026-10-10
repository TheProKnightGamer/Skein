package com.theproknightgamer.skein.core;

import net.minecraft.util.profiling.Profiler;
import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import java.util.Optional;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.entity.animal.dolphin.Dolphin;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.entity.EntityTickList;

import java.util.List;
import java.util.function.Consumer;

/**
 * Entities, across cells.
 *
 * <p>The whole design rests on one geometric argument: an entity's tick reads
 * and writes the world around it -- collision, block edits, area queries for
 * targets and paths -- and every one of those has a finite reach. If an entity
 * sits at least {@code borderMarginBlocks} inside its own cell, nothing it does
 * during that tick can touch a block or an entity section belonging to another
 * cell, so two cells on two threads cannot collide.
 *
 * <p>{@link Grid} is what makes that cover everything rather than a quarter of
 * everything, and {@link ParallelPass} runs the result.
 *
 * <p>The margin is the correctness knob, not a performance knob. It has to be
 * at least the furthest an entity reaches in a single tick; for vanilla mobs
 * that is follow range rather than movement speed, which is why the default is
 * 32 rather than something movement-sized. Several vanilla mobs carry a follow
 * range larger than that anyway, so an entity is filed by the reach it actually
 * has rather than by its position alone -- see {@link #reachBeyondMargin}.
 */
public final class EntityTicker {
    private EntityTicker() {}

    /**
     * Replacement for {@code entityTickList.forEach(action)} inside
     * {@code ServerLevel#tick}. {@code action} is vanilla's own per-entity
     * consumer, passed through untouched so despawn checks, tick freezing and
     * passenger handling all keep working exactly as they did.
     *
     * @param serial the loop as it would have run without Skein, including
     *               anything another mod wrapped around it
     */
    public static void run(ServerLevel level, EntityTickList list, Consumer<Entity> action, Runnable serial) {
        PhaseGovernor governor = ((SkeinLevel) level).skein$governor();
        boolean eligible = Skein.pool() != null
                && SkeinConfig.Live.enabled
                && SkeinConfig.Live.entities
                && SkeinCompat.allows(TickMetrics.Phase.ENTITIES)
                && !DimensionTicker.isQuarantined(level)
                && Profiler.get() instanceof InactiveProfiler;
        // Timed either way: the governor is choosing between two costs, so it
        // needs a figure for the path it is not currently taking.
        if (!governor.begin(TickMetrics.Phase.ENTITIES, eligible)) {
            try {
                serial.run();
            } finally {
                governor.finish(TickMetrics.Phase.ENTITIES);
            }
            return;
        }
        try {
            skein$run(level, list, action);
        } finally {
            governor.finish(TickMetrics.Phase.ENTITIES);
        }
    }

    private static void skein$run(ServerLevel level, EntityTickList list, Consumer<Entity> action) {

        SkeinLevel skeinLevel = (SkeinLevel) level;
        ClusterPass cluster = skeinLevel.skein$clusterPass();
        Partition<Entity> partition = skeinLevel.skein$entityPartition();
        partition.begin(SkeinConfig.Live.grid);
        LoadedAround.begin(level);

        // Snapshotted before anything runs. Vanilla's EntityTickList refuses
        // re-entrant iteration and swaps its backing map when something is
        // added mid-pass, so holding that iteration open across a parallel
        // phase would be a guaranteed ConcurrentModificationException. The
        // snapshot is also what lets the cluster tier decide whether it wants
        // the pile before the grid has filed it.
        List<Entity> snapshot = cluster.snapshot();
        List<Entity> candidates = cluster.candidates();
        List<Entity> others = cluster.others();
        snapshot.clear();
        candidates.clear();
        others.clear();
        try {
            list.forEach(snapshot::add);
            if (snapshot.size() < SkeinConfig.Live.minEntities) {
                runSerially(snapshot, action);
                return;
            }
            for (Entity entity : snapshot) {
                if (isClusterable(entity)) {
                    candidates.add(entity);
                } else {
                    others.add(entity);
                }
            }

            // Isolation and graph construction cannot pay off below the tier's
            // admission threshold. No entity has run when either step declines.
            boolean ranCluster = false;
            if (candidates.size() >= SkeinConfig.Live.minClusterEntities) {
                cluster.isolate(level, SkeinConfig.Live.clusterIsolation);
                ranCluster = candidates.size() >= SkeinConfig.Live.minClusterEntities
                        && cluster.run(level, candidates, action);
            }

            // The cluster tier closes every barrier before the grid tier starts.
            for (int i = 0; i < others.size(); i++) {
                file(partition, others.get(i));
            }
            if (!ranCluster) {
                for (int i = 0; i < candidates.size(); i++) {
                    file(partition, candidates.get(i));
                }
            }
            if (!ParallelPass.run(level, partition, action, TickMetrics.Phase.ENTITIES, "entity")) {
                runSerially(partition.all(), action);
            }
        } finally {
            // Also release on the small-population path and on tick failures.
            partition.release();
            snapshot.clear();
            candidates.clear();
            others.clear();
        }
    }

    private static void runSerially(List<Entity> entities, Consumer<Entity> action) {
        for (int i = 0; i < entities.size(); i++) {
            action.accept(entities.get(i));
        }
    }

    private static void file(Partition<Entity> partition, Entity entity) {
        if (isPinned(entity)) {
            partition.pin(entity);
            return;
        }
        double reach = reachBeyondMargin(entity);
        partition.place(entity,
                entity.getX() - reach, entity.getX() + reach,
                entity.getZ() - reach, entity.getZ() + reach);
    }

    /**
     * Whether this entity may be ordered by the conflict graph rather than by
     * the grid.
     *
     * <p>Deliberately a list of audited types and not a structural test. What
     * admits a type is that its entire tick -- where it moves, what it collides
     * with, what it does on hit, and what the block it is standing in does to
     * it -- stays inside {@code clusterReach}, and that it writes no blocks.
     * Neither is anything a mod's entity can be asked at runtime, and guessing
     * wrong is a race rather than a slowdown, so the set is opt-in.
     */
    private static boolean isClusterable(Entity entity) {
        return SkeinConfig.Live.clusterEntities
                && !isPinned(entity)
                && !SkeinConfig.Live.clusterTypes.isEmpty()
                && SkeinConfig.Live.clusterTypes.containsKey(entity.getType());
    }

    /**
     * How much further than the margin this entity's tick can reach, as a
     * footprint to be filed by rather than as a point.
     *
     * <p>Follow range is what the margin is sized against, and several vanilla
     * mobs carry one larger than the default: a zombie alerts every other
     * zombie within thirty-five blocks and writes a target into it, a bee or a
     * blaze does the same at forty-eight, and an enderman and a ghast see
     * further still. Filed as points, two such mobs in adjacent cells of one
     * pass sit exactly two margins apart and both write to whatever is between
     * them, which is the one thing the geometry promises cannot happen.
     *
     * <p>Filing the excess as a span instead makes such a mob sit its whole
     * reach inside its own cell, or fall to the serial set when no cell has
     * room for it. That costs coverage for the mobs that overrun the margin
     * rather than correctness for the rest, and it covers modded mobs, which a
     * blacklist cannot.
     */
    private static double reachBeyondMargin(Entity entity) {
        double reach = Math.max(0.0, bodyReach(entity) - SkeinConfig.Live.borderMargin);
        if (!(entity instanceof LivingEntity living)
                || !living.getAttributes().hasAttribute(Attributes.FOLLOW_RANGE)) {
            return reach;
        }
        return Math.max(reach,
                living.getAttributeValue(Attributes.FOLLOW_RANGE) - SkeinConfig.Live.borderMargin);
    }

    /**
     * How far from its position an entity's own body goes, and a few blocks
     * past it for what acts at its edge.
     *
     * <p>A mob is a block or two wide and this never matters. A moving
     * structure is not: a Create contraption's box covers everything it
     * carries, up to hundreds of blocks of windmill or drilling rig, and its
     * drills, harvesters and deployers act anywhere inside it -- as do the
     * ships and vehicles of other mods. Filed as a point, two of those in
     * different cells of one pass could reach the same blocks. Filed by their
     * box, they keep to their own cell or tick on the level thread.
     */
    private static double bodyReach(Entity entity) {
        AABB box = entity.getBoundingBox();
        double x = entity.getX();
        double z = entity.getZ();
        double body = Math.max(Math.max(box.maxX - x, x - box.minX), Math.max(box.maxZ - z, z - box.minZ));
        // A box nothing can file: more than any cell, so the serial set.
        return Double.isFinite(body) ? Math.min(body, 65536.0) + 3.0 : 65536.0;
    }

    private static boolean likesUnloadedNoteBlock(Allay allay) {
        Optional<GlobalPos> liked = allay.getBrain().getMemory(MemoryModuleType.LIKED_NOTEBLOCK_POSITION);
        return liked.isPresent() && liked.get().dimension() == allay.level().dimension()
                && !allay.level().hasChunkAt(liked.get().pos());
    }

    /** Entities that must never leave the level-owning thread. */
    private static boolean isPinned(Entity entity) {
        if (entity.isMultipartEntity() || entity.getVehicle() != null || !entity.getPassengers().isEmpty()) {
            // Multipart bosses own colliders that live outside their own chunk,
            // and anything in a riding stack ticks its passengers itself, so
            // the stack has to stay on one thread.
            return true;
        }
        if (SkeinConfig.Live.pinPlayers && entity instanceof ServerPlayer) {
            // A player tick reaches well past the player: advancements, stats,
            // the open container, the game-mode state machine. None of that is
            // bounded by the margin the geometry rests on, and there are never
            // enough players for it to be worth the risk.
            return true;
        }
        if (entity instanceof SkeinVillager villager && villager.skein$levelsUpThisTick()) {
            // Levelling up rolls the new trades, and a cartographer's explorer
            // map searches for a structure up to a hundred chunks away,
            // loading chunks as it goes -- which only the level thread may do.
            // Refused on a worker, the level would be gained and its trades
            // lost for good.
            return true;
        }
        if (entity instanceof ThrownEnderpearl) {
            // A pearl in flight renews a chunk ticket in its thrower's name,
            // writing the thrower's own set of pearls, and its landing moves
            // the thrower: both reach an entity that may be in another cell.
            // There are only ever a few.
            return true;
        }
        if (entity instanceof Allay allay && likesUnloadedNoteBlock(allay)) {
            // An allay checks its liked note block every time it decides where
            // to take items, before it checks whether the liking has expired,
            // so a note block left behind in an unloaded chunk is loaded by
            // vanilla -- and refused on a worker, which would end every tick
            // of the allay there for good, before it forgets the block.
            return true;
        }
        if (entity instanceof Dolphin dolphin && dolphin.gotFish()) {
            // A fed dolphin's next goal searches up to fifty chunks away for a
            // treasure to lead the player to, loading chunks as it goes.
            // Refused on a worker, the goal would start without a target and
            // swim the player to the last one -- the origin for a new dolphin.
            return true;
        }
        if (entity instanceof SkeinExplosive explosive && ExplosionRays.rayReach(explosive.skein$explosionRadius())
                > Math.max(SkeinConfig.Live.loadedMarginChunks, 2) * 16) {
            // An explosion whose rays can run past the chunks the loaded margin
            // guarantees -- a creeper or fireball given a huge radius -- may
            // need a chunk loaded, and only the level thread may do that. With
            // no margin configured, vanilla's own guarantee still holds: two
            // rings of loaded chunks around every entity-ticking one.
            return true;
        }
        if (entity.level() instanceof ServerLevel level && !LoadedAround.test(level, entity)) {
            // Close enough to the edge of the loaded area that its tick may
            // read into an unloaded chunk, which only the level thread may load.
            return true;
        }
        return !SkeinConfig.Live.entityBlacklist.isEmpty()
                && SkeinConfig.Live.entityBlacklist.contains(entity.getType())
                || Demotions.entity(entity.getType());
    }
}
