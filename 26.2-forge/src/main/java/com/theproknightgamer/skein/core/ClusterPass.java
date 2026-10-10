package com.theproknightgamer.skein.core;

import java.util.function.LongPredicate;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.util.Mth;
import net.minecraft.core.BlockPos;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.util.Util;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.PartEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

/**
 * Runs the entities {@link EntityLevels} has levelled, one level at a time.
 *
 * <p>This is {@link ParallelPass} with the geometry taken out. There, a task is
 * a cell and the barriers are the four grid passes; here a task is a slice of
 * one precedence level and the barriers are the levels. Everything else is the
 * same, deliberately: each task ticks inside {@link TickContext#enter} with its
 * own {@link DeferredBuffer}, the coordinator drains the buffers in task order
 * at every barrier, and the crash policy decides whether one thrown entity
 * takes the tick down.
 *
 * <p>The buffers are per <em>slice</em> rather than per cell, which is the only
 * structural difference worth noting. A level is split across the pool, so a
 * single buffer would be written by every worker at once; giving each slice its
 * own keeps them unsynchronised, and draining them in slice order keeps the
 * replay deterministic.
 */
public final class ClusterPass {

    /** Small enough that the pool stays fed, large enough that a task is worth dispatching. */
    private static final int MIN_SLICE = 16;

    /**
     * A level is a barrier, so the tier is only worth it when the average level
     * is wide enough to fill the pool several times over. A pile that levels
     * narrowly -- items packed tighter than they merge, say -- is better served
     * by the grid, which at least gets one thread per cell without a barrier
     * between every handful of entities.
     */
    private static final int MIN_WIDTH_PER_WORKER = 2;

    private final EntityLevels levels = new EntityLevels();
    private final List<DeferredBuffer> buffers = new ArrayList<>();
    private final List<Entity> snapshot = new ArrayList<>();
    private final List<Entity> candidates = new ArrayList<>();
    private final List<Entity> others = new ArrayList<>();
    private final PointGrid outsiders = new PointGrid();

    /** {@code ProjectileUtil.getHitResultOnMoveVector} grows every candidate target's hitbox by this. */
    private static final double HIT_INFLATION = 0.3;

    /**
     * How far a projectile's path may drift between isolation and its own hit
     * test. Fluid pushing is the only thing that moves it, by at most
     * {@code FluidType.motionScale} -- 0.014 for water -- per fluid touched.
     */
    private static final double PATH_ALLOWANCE = 0.25;

    /** Outsider hitboxes as {@code minX, minY, minZ, maxX, maxY, maxZ}, indexed like {@link #outsiders}. */
    private double[] hitboxes = new double[6 * 64];
    /** How far the widest outsider hitbox reaches from its own entity's position, per axis. */
    private double hitboxReach;
    private final PathTest pathTest = new PathTest();

    /** This tick's entity list, copied out of {@code EntityTickList} before anything runs. */
    public List<Entity> snapshot() {
        return this.snapshot;
    }

    /** The subset of {@link #snapshot()} eligible for this tier. */
    public List<Entity> candidates() {
        return this.candidates;
    }

    /** Everything the grid tier files: the ineligible, the pinned, and anything demoted. */
    public List<Entity> others() {
        return this.others;
    }

    /**
     * Moves out of {@link #candidates()} anything sitting close to a gridded
     * entity, into {@link #others()}.
     *
     * <p>The tier's conflict test only asks how far a clustered entity reaches
     * toward <em>another clustered</em> entity, and for a projectile that is
     * zero -- it can only hit what is pickable and nothing here is. It can
     * still hit a mob, though, and two projectiles that provably cannot touch
     * each other are placed on the same level and run at the same instant, so
     * both can be inside {@code LivingEntity.hurt} on the same mob at once.
     * That trips the victim's own {@code ThreadingDetector}, which is how this
     * was found rather than reasoned out.
     *
     * <p>Rather than widen every projectile's reach -- which would re-tangle
     * the graph that makes the tier worth having -- the tier simply declines
     * the entities that are near anything it does not own.
     *
     * <p>For vanilla snowballs and eggs "near" is what they can actually hit
     * this tick rather than {@code reach}; see {@link #mayHitOutsider}. A
     * radius is far too blunt for the farm this tier exists for: a player
     * watching a bubble column from two blocks away is within any sane radius
     * of every snowball in it, and handing the whole column back to the grid
     * puts it in one cell on one thread.
     *
     * <p>Outsiders are the entities this tick will run, which is not every
     * entity a projectile can hit: an entity in a chunk that is loaded but not
     * entity-ticking never enters the tick list, and a snowball's hit query
     * finds it all the same. Two clustered snowballs hitting one frozen mob or
     * item frame at the same instant is the race above with a dupe on top, so
     * a candidate that could reach such a chunk goes to the grid, whose
     * separation covers it. So does an entity that merges with its own kind
     * but is moving too fast for its reach to stay small; see {@link #reachOf}.
     */
    public void isolate(ServerLevel level, double reach) {
        BlockPos.MutableBlockPos probe = this.probe;
        isolate(chunk -> level.isPositionEntityTicking(
                probe.set(ChunkPos.getX(chunk) << 4, 0, ChunkPos.getZ(chunk) << 4)), reach);
    }

    /** {@link #isolate(ServerLevel, double)} with the entity-ticking question supplied, for tests. */
    public void isolate(LongPredicate entityTicking, double reach) {
        if (this.candidates.isEmpty()) {
            return;
        }
        boolean anyOutsiders = !this.others.isEmpty();
        if (anyOutsiders) {
            this.outsiders.build(this.others, reach);
        }
        boolean exact = SkeinConfig.Live.exactProjectileIsolation;
        if (anyOutsiders) {
            indexHitboxes();
        }
        // Something in a chunk that is not entity-ticking is hit by its box,
        // and the box can reach this far from the chunk it is filed under.
        double unseen = widestHitbox();
        this.ticking.clear();
        int kept = 0;
        for (int i = 0; i < this.candidates.size(); i++) {
            Entity entity = this.candidates.get(i);
            boolean near;
            if (exact && ProjectileQueries.ordinaryThrowable(entity)) {
                setPath(entity);
                PathTest path = this.pathTest;
                near = !Double.isFinite(path.minX + path.maxX + path.minZ + path.maxZ)
                        || !allTicking(entityTicking, path.minX - unseen, path.minZ - unseen,
                                path.maxX + unseen, path.maxZ + unseen)
                        || anyOutsiders && mayHitOutsider();
            } else {
                // Outsiders are indexed by position, and a large one is hit
                // well away from it: the query widens by the widest box.
                double x = entity.getX(), z = entity.getZ();
                double wide = reach + unseen;
                near = tooFast(entity)
                        || !allTicking(entityTicking, x - wide, z - wide, x + wide, z + wide)
                        || anyOutsiders && this.outsiders.anyWithin(x, entity.getY(), z, reach + this.hitboxReach);
            }
            if (near) {
                this.others.add(entity);
            } else {
                this.candidates.set(kept++, entity);
            }
        }
        while (this.candidates.size() > kept) {
            this.candidates.remove(this.candidates.size() - 1);
        }
    }

    /**
     * Half the widest hitbox any registered entity type has, capped at a
     * chunk: how far outside the chunk an entity is filed under a hit can
     * still land on it. Worked out once; the registry is frozen by then.
     */
    private static volatile double widestHitbox = -1.0;

    private static double widestHitbox() {
        double widest = widestHitbox;
        if (widest < 0.0) {
            widest = 0.0;
            for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                widest = Math.max(widest, type.getDimensions().width() * 0.5);
            }
            widest = Math.min(widest, 16.0);
            widestHitbox = widest;
        }
        return widest;
    }

    /**
     * Records every outsider's hitbox, in the order {@link #outsiders} indexed
     * them, and how far the widest one reaches from its own position.
     *
     * <p>Multipart bosses are hit through their parts, which
     * {@code Level.getEntities} finds by their own boxes and which can hang
     * well outside the parent's, so a parent's box is widened to cover them.
     */
    private void indexHitboxes() {
        int count = this.others.size();
        if (this.hitboxes.length < count * 6) {
            this.hitboxes = new double[Math.max(count * 6, this.hitboxes.length * 2)];
        }
        double widest = 0.0;
        for (int i = 0; i < count; i++) {
            Entity entity = this.others.get(i);
            AABB box = entity.getBoundingBox();
            double minX = box.minX, minY = box.minY, minZ = box.minZ;
            double maxX = box.maxX, maxY = box.maxY, maxZ = box.maxZ;
            PartEntity<?>[] parts = entity.isMultipartEntity() ? entity.getParts() : null;
            if (parts != null) {
                for (PartEntity<?> part : parts) {
                    AABB partBox = part.getBoundingBox();
                    minX = Math.min(minX, partBox.minX);
                    minY = Math.min(minY, partBox.minY);
                    minZ = Math.min(minZ, partBox.minZ);
                    maxX = Math.max(maxX, partBox.maxX);
                    maxY = Math.max(maxY, partBox.maxY);
                    maxZ = Math.max(maxZ, partBox.maxZ);
                }
            }
            int at = i * 6;
            this.hitboxes[at] = minX;
            this.hitboxes[at + 1] = minY;
            this.hitboxes[at + 2] = minZ;
            this.hitboxes[at + 3] = maxX;
            this.hitboxes[at + 4] = maxY;
            this.hitboxes[at + 5] = maxZ;
            double x = entity.getX(), y = entity.getY(), z = entity.getZ();
            widest = Math.max(widest, Math.max(Math.max(x - minX, maxX - x),
                    Math.max(Math.max(y - minY, maxY - y), Math.max(z - minZ, maxZ - z))));
        }
        this.hitboxReach = widest;
    }

    /**
     * Whether a vanilla snowball or egg could hit any outsider this tick.
     *
     * <p>Hitting is the only way one writes to an entity outside this tier: its
     * tick reads nearby entities to choose a target, but reads are harmless
     * while nothing else in the tier writes them either. And
     * {@code ProjectileUtil.getHitResultOnMoveVector} hits only an entity whose
     * hitbox, grown by {@value #HIT_INFLATION}, crosses the segment from the
     * projectile's position along its motion. So the test is the box spanning
     * that segment, padded by the inflation plus {@value #PATH_ALLOWANCE} of
     * allowance, against every outsider's hitbox.
     *
     * <p>Nothing else moves the segment between here and the hit test. Fluids
     * push the projectile by a hundredth of a block or so first, which the
     * allowance covers many times over. Portal travel is parked to the barrier,
     * so the projectile cannot teleport before it looks for a target. Other
     * clustered entities cannot hit it or push it. Outsiders do not tick until
     * this tier has closed.
     */
    private void setPath(Entity projectile) {
        Vec3 motion = projectile.getDeltaMovement();
        double x = projectile.getX(), y = projectile.getY(), z = projectile.getZ();
        double pad = HIT_INFLATION + PATH_ALLOWANCE;
        PathTest path = this.pathTest;
        path.minX = Math.min(x, x + motion.x) - pad;
        path.minY = Math.min(y, y + motion.y) - pad;
        path.minZ = Math.min(z, z + motion.z) - pad;
        path.maxX = Math.max(x, x + motion.x) + pad;
        path.maxY = Math.max(y, y + motion.y) + pad;
        path.maxZ = Math.max(z, z + motion.z) + pad;
    }

    /** Tests the path {@link #setPath} left in {@link #pathTest}. */
    private boolean mayHitOutsider() {
        PathTest path = this.pathTest;
        double cx = (path.minX + path.maxX) * 0.5;
        double cy = (path.minY + path.maxY) * 0.5;
        double cz = (path.minZ + path.maxZ) * 0.5;
        double half = Math.max(path.maxX - cx, Math.max(path.maxY - cy, path.maxZ - cz));
        if (!Double.isFinite(half) || !Double.isFinite(cx) || !Double.isFinite(cy) || !Double.isFinite(cz)) {
            return true;
        }
        // A hitbox touching the path belongs to an outsider positioned within
        // this Chebyshev distance of the path's centre; the grid finds those and
        // the path test decides. The slack absorbs rounding in the subtraction.
        return this.outsiders.anyWithin(cx, cy, cz, half + this.hitboxReach + 1.0e-3, path);
    }

    /** Per-chunk answers for {@link #allTicking}, for one {@link #isolate} call. */
    private final Long2ByteOpenHashMap ticking = new Long2ByteOpenHashMap();
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

    /** Whether every chunk the rectangle touches is entity-ticking, so everything in it is in the tick list. */
    private boolean allTicking(LongPredicate entityTicking, double minX, double minZ, double maxX, double maxZ) {
        int fromX = Mth.floor(minX) >> 4, toX = Mth.floor(maxX) >> 4;
        int fromZ = Mth.floor(minZ) >> 4, toZ = Mth.floor(maxZ) >> 4;
        for (int cx = fromX; cx <= toX; cx++) {
            for (int cz = fromZ; cz <= toZ; cz++) {
                long key = ChunkPos.pack(cx, cz);
                byte known = this.ticking.get(key);
                if (known == 0) {
                    known = entityTicking.test(key) ? (byte) 1 : (byte) 2;
                    this.ticking.put(key, known);
                }
                if (known != 1) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Faster than this, an entity that merges with its own kind would need a
     * reach wide enough to tangle the graph and swell every bucket; it goes to
     * the grid instead.
     */
    private static final double FAST = 2.0;

    private static boolean tooFast(Entity entity) {
        return SkeinConfig.Live.clusterRadius(entity.getType()) > 0.0 && mayMerge(entity)
                && entity.getDeltaMovement().lengthSqr() > FAST * FAST;
    }

    /**
     * Whether this entity's reach toward its own kind is a merge that could
     * happen this tick. Vanilla's own answer for an item -- one that can never
     * be picked up, or has stopped ageing, never merges; an orb or a modded
     * type is assumed to.
     */
    private static boolean mayMerge(Entity entity) {
        return !(entity instanceof SkeinMergeable mergeable) || mergeable.skein$mayMerge();
    }

    /**
     * How far this entity reaches toward another clustered entity this tick.
     *
     * <p>The configured radius covers the interaction itself and ordinary
     * movement, but items and orbs have no speed cap: two thrown by one blast
     * can close several blocks in a tick and merge from well outside it. Two
     * entities conflict when either one's reach covers the other, so each adds
     * twice its own speed -- the faster of any pair then covers both moves --
     * plus a step of gravity. An entity with no reach toward its own kind keeps
     * none, however fast it goes.
     */
    private static double reachOf(Entity entity) {
        double base = SkeinConfig.Live.clusterRadius(entity.getType());
        if (base <= 0.0 || !mayMerge(entity)) {
            return Math.max(0.0, base);
        }
        return base + 2.0 * entity.getDeltaMovement().length() + 0.1;
    }

    /** Reused for every query: this runs per projectile per tick on the coordinator. */
    private final class PathTest implements IntPredicate {
        double minX, minY, minZ, maxX, maxY, maxZ;

        @Override
        public boolean test(int index) {
            double[] box = ClusterPass.this.hitboxes;
            int at = index * 6;
            return box[at] <= this.maxX && box[at + 3] >= this.minX
                    && box[at + 1] <= this.maxY && box[at + 4] >= this.minY
                    && box[at + 2] <= this.maxZ && box[at + 5] >= this.minZ;
        }
    }

    /**
     * @return true when the pile was levelled and run, false when it declined
     *         and the caller still owns every entity in it
     */
    public boolean run(ServerLevel level, List<Entity> items, Consumer<Entity> action) {
        SkeinPool pool = Skein.pool();
        if (pool == null || items.size() < 2) {
            return false;
        }
        int maxLevels = (int) Math.min(Integer.MAX_VALUE,
                items.size() / Math.max(1L, (long) MIN_WIDTH_PER_WORKER * pool.size()));
        int levelCount = this.levels.build(items, ClusterPass::reachOf, maxLevels);
        if (levelCount <= 0) {
            return false;
        }
        int[] order = this.levels.order();
        int[] start = this.levels.levelStart();
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        int dispatched = 0;
        int deferred = 0;
        int ran = 0;

        for (int l = 0; l < levelCount; l++) {
            int from = start[l];
            int to = start[l + 1];
            int count = to - from;
            if (count <= 0) {
                continue;
            }
            ran += count;

            // Cut from this level's width, not the whole pile. Sized off the
            // total, a narrow level becomes a single task and runs on the
            // coordinator alone however many workers are free -- which is
            // exactly the mixed-pile case where the levels that matter are
            // narrow.
            int slice = Math.max(MIN_SLICE, count / Math.max(1, pool.size() * 4));

            int used = (count + slice - 1) / slice;
            buffer(used - 1); // Grow on the coordinator before workers read it.
            dispatched += used;

            long wallStart = Util.getNanos();
            pool.invokeAll(used, index -> {
                int taskFrom = from + index * slice;
                runSlice(level, items, order, taskFrom, Math.min(taskFrom + slice, to),
                        action, this.buffers.get(index), fatal);
            });
            TickMetrics.recordPhaseWall(TickMetrics.Phase.ENTITIES, Util.getNanos() - wallStart);

            TickContext.Local local = TickContext.local();
            DeferredBuffer previousHold = local.heldSpawns;
            local.heldSpawns = this.spawns;
            try {
                for (int i = 0; i < used; i++) {
                    deferred += this.buffers.get(i).drain(level, this.spawns);
                }
            } finally {
                local.heldSpawns = previousHold;
            }

            Throwable throwable = fatal.get();
            if (throwable != null) {
                discard();
                throw new ReportedException(CrashReport.forThrowable(
                        throwable, "Exception ticking entity (Skein cluster tier)"));
            }
        }

        // New entities join only now: isolation was decided before they
        // existed, so a later level's projectiles must not find them.
        deferred += this.spawns.drain(level);
        TickMetrics.recordCluster(levelCount, dispatched, ran);
        return true;
    }

    private DeferredBuffer buffer(int index) {
        while (this.buffers.size() <= index) {
            this.buffers.add(new DeferredBuffer());
        }
        return this.buffers.get(index);
    }

    /** Drops anything a crashing level parked but never applied, so it is not replayed next tick. */
    private void discard() {
        for (DeferredBuffer buffer : this.buffers) {
            buffer.clear();
        }
        this.spawns.clear();
    }

    /** Spawns parked by every level of the tier, held until its last one has run. */
    private final DeferredBuffer spawns = new DeferredBuffer();

    private static void runSlice(ServerLevel level,
                                 List<Entity> items,
                                 int[] order,
                                 int from,
                                 int to,
                                 Consumer<Entity> action,
                                 DeferredBuffer buffer,
                                 AtomicReference<Throwable> fatal) {
        long start = Util.getNanos();
        TickContext.Local local = TickContext.local();
        long hits = local.chunkHits;
        long misses = local.chunkMisses;
        long lockNanos = local.chunkLockNanos;
        TickContext.State previous = TickContext.enter(level, true, buffer, true);
        try {
            for (int i = from; i < to; i++) {
                Entity entity = items.get(order[i]);
                local.ticking = entity;
                try {
                    action.accept(entity);
                } catch (Throwable t) {
                    if (SkeinRefusal.in(t)) {
                        TickContext.resetBrackets();
                        SkeinRefusal.swallowed(entity, t);
                        continue;
                    }
                    TickMetrics.recordFailure();
                    if (SkeinConfig.Live.crashPolicy == SkeinConfig.CrashPolicy.RETHROW) {
                        fatal.compareAndSet(null, t);
                        return;
                    }
                    TickContext.resetBrackets();
                    Skein.LOGGER.error("Skein: {} threw during clustered entity tick in {}",
                            entity, level.dimension().identifier(), t);
                    Demotions.threw(entity, level, t);
                }
            }
        } finally {
            local.ticking = null;
            TickContext.exit(previous);
            TickMetrics.recordChunkAccess(local.chunkHits - hits, local.chunkMisses - misses,
                    local.chunkLockNanos - lockNanos);
            TickMetrics.recordPhaseWork(TickMetrics.Phase.ENTITIES, Util.getNanos() - start);
        }
    }
}
