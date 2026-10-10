package com.theproknightgamer.skein.core;

import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.util.Mth;
import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EntityBasedExplosionDamageCalculator;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.RandomSource;

import java.util.List;
import java.util.Optional;

/**
 * An explosion's rays, cast across the pool.
 *
 * <p>Vanilla casts 1,352 rays from the centre of every explosion, one after
 * another, each stepping outward and reading the block it is in until its
 * power runs out. The rays only read: nothing is broken until
 * {@code finalizeExplosion}. What makes them sequential is the order of their
 * results -- blocks go into the affected set in ray order, and that order is
 * the order they later break and drop in -- and one random draw per ray, taken
 * from the level in ray order.
 *
 * <p>So the draws are taken first, in that order, on the calling thread, which
 * leaves the level's random stream exactly where vanilla leaves it. The rays
 * are then cast in contiguous slices on the pool, each slice collecting its
 * hits in ray order, and the caller appends each block's first hit to the
 * affected list, slice after slice: the list Skein's serial loop builds, whose
 * set keeps first-insertion order. (Vanilla's {@code HashSet} iterates in hash
 * order instead, which the shuffle in {@code finalizeExplosion} erases.)
 *
 * <p>This is for when explosions cannot run side by side: a column of TNT
 * stacked two blocks apart is a single chain, each blast moving the next charge
 * and breaking the blocks it will read, so it runs on one thread whatever the
 * grid does. Its rays can still use every idle one.
 *
 * <p>Helpers read the world as any Skein worker does -- inside a
 * {@link TickContext} for the level, through the per-thread chunk cache -- and
 * whatever they park (a chunk ticket) is handed to the caller's buffer in slice
 * order, where the caller would have parked it. Only vanilla damage
 * calculators qualify, with no source or a vanilla TNT or creeper as the
 * source: the rays call the calculator from several threads at once and out of
 * order, and those are known to only compute.
 */
public final class ExplosionRays {
    private ExplosionRays() {}

    /** Rays per explosion: the surface of vanilla's 16-cube of directions. */
    public static final int RAYS = 1352;
    /** Enough slices to spread over the pool without making each one trivial. */
    private static final int SLICE = 64;

    private static final java.util.concurrent.atomic.LongAdder CASTS = new java.util.concurrent.atomic.LongAdder();

    /** Implemented by {@code Explosion}: the exposure answers of the explosion currently being worked out. */
    public interface Exposed {
        Exposure skein$exposure();
    }

    /** Explosions whose rays have been cast on the pool, for tests and benchmarks. */
    public static long casts() {
        return CASTS.sum();
    }

    private static final double[] DIR_X = new double[RAYS];
    private static final double[] DIR_Y = new double[RAYS];
    private static final double[] DIR_Z = new double[RAYS];

    static {
        // Vanilla's arithmetic exactly, in vanilla's loop order.
        int ray = 0;
        for (int j = 0; j < 16; j++) {
            for (int k = 0; k < 16; k++) {
                for (int l = 0; l < 16; l++) {
                    if (j == 0 || j == 15 || k == 0 || k == 15 || l == 0 || l == 15) {
                        double d0 = (double) ((float) j / 15.0F * 2.0F - 1.0F);
                        double d1 = (double) ((float) k / 15.0F * 2.0F - 1.0F);
                        double d2 = (double) ((float) l / 15.0F * 2.0F - 1.0F);
                        double d3 = Math.sqrt(d0 * d0 + d1 * d1 + d2 * d2);
                        DIR_X[ray] = d0 / d3;
                        DIR_Y[ray] = d1 / d3;
                        DIR_Z[ray] = d2 / d3;
                        ray++;
                    }
                }
            }
        }
        if (ray != RAYS) {
            throw new IllegalStateException("explosion ray table has " + ray + " rays");
        }
    }

    /** Whether this explosion's rays may be cast off the calling thread. */
    public static boolean eligible(Level level, ExplosionDamageCalculator calculator, Entity source) {
        if (!SkeinConfig.Live.parallelExplosionRays || !Hooks.allows(Hooks.Feature.EXPLOSION_RAYS) || level.isClientSide || level.isDebug() || Skein.pool() == null || Skein.pool().size() < 2
                || DimensionTicker.isQuarantined(level)) {
            return false;
        }
        Class<?> type = calculator.getClass();
        if (type == ExplosionDamageCalculator.class) {
            return true;
        }
        if (type != EntityBasedExplosionDamageCalculator.class || source == null) {
            return false;
        }
        Class<?> sourceType = source.getClass();
        return sourceType == PrimedTnt.class || sourceType == Creeper.class;
    }

    /**
     * Casts every ray and returns what they hit, to be appended to the
     * explosion's affected blocks with {@link Hits#addTo}.
     */
    public static Hits cast(Explosion explosion, Level level, ExplosionDamageCalculator calculator,
                            double x, double y, double z, float radius) {
        // The helpers run deferring, and a deferring thread may not load a
        // chunk. So every chunk a ray can reach has to be there already, or
        // the rays are left to vanilla's loop on this thread, which may load
        // them if this thread is allowed to. Checked before any draw, so that
        // declining takes nothing from the random stream.
        int reach = rayReach(radius);
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        if (!published(level, bx - reach, bz - reach, bx + reach, bz + reach)) {
            return null;
        }
        CASTS.increment();
        // Resolved once: every draw below is on this thread, inside the same
        // context, so each would have picked the same source.
        RandomSource random = level.random instanceof SkeinRandom skein ? skein.current() : level.random;
        float[] power = new float[RAYS];
        if (BulkRandom.confined(level)) {
            BulkRandom.nextFloats(random, power);
        } else {
            for (int ray = 0; ray < RAYS; ray++) {
                power[ray] = random.nextFloat();
            }
        }
        for (int ray = 0; ray < RAYS; ray++) {
            power[ray] = radius * (0.7F + power[ray] * 0.6F);
        }
        int slices = (RAYS + SLICE - 1) / SLICE;
        LongArrayList[] hits = new LongArrayList[slices];
        DeferredBuffer[] parked = new DeferredBuffer[slices];
        Skein.pool().invokeAll(slices, slice -> {
            DeferredBuffer buffer = new DeferredBuffer();
            parked[slice] = buffer;
            TickContext.State previous = TickContext.enter(level, true, buffer);
            try {
                hits[slice] = castSlice(explosion, level, calculator, x, y, z, power,
                        slice * SLICE, Math.min(RAYS, (slice + 1) * SLICE));
            } finally {
                TickContext.exit(previous);
            }
        });

        // What the helpers parked joins the caller's own buffer in ray order,
        // or is applied now if the caller is not inside a pass.
        DeferredBuffer own = TickContext.deferring() ? TickContext.buffer() : null;
        for (DeferredBuffer buffer : parked) {
            if (buffer == null || buffer.isEmpty()) {
                continue;
            }
            if (own != null) {
                buffer.transferTo(own);
            } else {
                buffer.drain(level);
            }
        }
        return new Hits(hits, (int) Math.floor(x) - 8, (int) Math.floor(y) - 8, (int) Math.floor(z) - 8);
    }

    /**
     * One explosion's ray hits, slice by slice in ray order, each slice
     * already rid of its own repeats inside the 16-cube around the centre.
     */
    public static final class Hits {
        private final LongArrayList[] slices;
        private final int minX;
        private final int minY;
        private final int minZ;

        private Hits(LongArrayList[] slices, int minX, int minY, int minZ) {
            this.slices = slices;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
        }

        /**
         * Appends each block's first hit to {@code toBlow}, in ray order: the
         * list the serial path's insertion-ordered set produces, built without
         * the set. Almost every hit of a blast in open air is an air block,
         * about fourteen hundred of them, and hashing each into a set was more
         * than a third of the explosion.
         */
        public void addTo(ObjectArrayList<BlockPos> toBlow) {
            int total = 0;
            for (LongArrayList slice : this.slices) {
                total += slice.size();
            }
            toBlow.ensureCapacity(toBlow.size() + total);
            long[] seen = new long[64];
            // Only a blast wider than the cube reaches past it; the serial
            // path's set drops those repeats, and so does this.
            LongOpenHashSet outside = null;
            for (LongArrayList slice : this.slices) {
                long[] packed = slice.elements();
                for (int i = 0, n = slice.size(); i < n; i++) {
                    long pos = packed[i];
                    int x = BlockPos.getX(pos);
                    int y = BlockPos.getY(pos);
                    int z = BlockPos.getZ(pos);
                    if (inCube(x, y, z, this.minX, this.minY, this.minZ)) {
                        if (!firstInCube(seen, x, y, z, this.minX, this.minY, this.minZ)) {
                            continue;
                        }
                    } else {
                        if (outside == null) {
                            outside = new LongOpenHashSet();
                        }
                        if (!outside.add(pos)) {
                            continue;
                        }
                    }
                    toBlow.add(new BlockPos(x, y, z));
                }
            }
        }
    }

    private static boolean inCube(int x, int y, int z, int minX, int minY, int minZ) {
        return (((long) x - minX) | ((long) y - minY) | ((long) z - minZ)) >>> 4 == 0;
    }

    /**
     * Whether this is the first time {@code pos} is seen, for positions in the
     * 16-cube around the explosion that the serial path deduplicates too.
     * Anything outside it always passes, and the set drops its repeats.
     */
    private static boolean firstInCube(long[] seen, int x, int y, int z, int minX, int minY, int minZ) {
        long dx = (long) x - minX;
        long dy = (long) y - minY;
        long dz = (long) z - minZ;
        if ((dx | dy | dz) >>> 4 != 0) {
            return true;
        }
        int index = (int) (dx | dy << 4 | dz << 8);
        long bit = 1L << index;
        int word = index >>> 6;
        if ((seen[word] & bit) != 0) {
            return false;
        }
        seen[word] |= bit;
        return true;
    }

    /** A slice's hits as packed positions: the merge reads them back without chasing an object per hit. */
    private static LongArrayList castSlice(Explosion explosion, Level level, ExplosionDamageCalculator calculator,
                                           double x, double y, double z, float[] power, int from, int to) {
        LongArrayList hits = new LongArrayList(256);
        // A ray revisits a block every 0.3 of its travel, and rays overlap near
        // the centre: tens of thousands of hits for about fifteen hundred
        // blocks. Only each block's first hit can change the set.
        int minX = (int) Math.floor(x) - 8;
        int minY = (int) Math.floor(y) - 8;
        int minZ = (int) Math.floor(z) - 8;
        long[] seen = new long[64];
        long[] heldPositions = new long[4];
        LevelChunk[] heldChunks = new LevelChunk[4];
        int held = 0;
        for (int ray = from; ray < to; ray++) {
            double d0 = DIR_X[ray];
            double d1 = DIR_Y[ray];
            double d2 = DIR_Z[ray];
            float f = power[ray];
            double d4 = x;
            double d6 = y;
            double d8 = z;
            for (; f > 0.0F; f -= 0.22500001F) {
                BlockPos pos = BlockPos.containing(d4, d6, d8);
                BlockState state;
                if (level.isOutsideBuildHeight(pos)) {
                    state = Blocks.VOID_AIR.defaultBlockState();
                } else {
                    int chunkX = pos.getX() >> 4;
                    int chunkZ = pos.getZ() >> 4;
                    long position = ChunkPos.asLong(chunkX, chunkZ);
                    LevelChunk chunk = null;
                    for (int i = 0; i < held; i++) {
                        if (heldPositions[i] == position) {
                            chunk = heldChunks[i];
                            break;
                        }
                    }
                    if (chunk == null) {
                        chunk = level.getChunk(chunkX, chunkZ);
                        if (held < heldChunks.length) {
                            heldPositions[held] = position;
                            heldChunks[held++] = chunk;
                        }
                    }
                    state = chunk.getBlockState(pos);
                }
                // As in the serial path: the fluid is the block's own; debug
                // worlds, which synthesise states, never come here.
                FluidState fluid = state.getFluidState();
                if (!level.isInWorldBounds(pos)) {
                    break;
                }
                Optional<Float> resistance = calculator.getBlockExplosionResistance(explosion, level, pos, state, fluid);
                if (resistance.isPresent()) {
                    f -= (resistance.get() + 0.3F) * 0.3F;
                }
                if (f > 0.0F && calculator.shouldBlockExplode(explosion, level, pos, state, f)
                        && firstInCube(seen, pos.getX(), pos.getY(), pos.getZ(), minX, minY, minZ)) {
                    hits.add(pos.asLong());
                }
                d4 += d0 * 0.3F;
                d6 += d1 * 0.3F;
                d8 += d2 * 0.3F;
            }
        }
        return hits;
    }

    /**
     * Exposure answers for one explosion's nearby entities, computed ahead and
     * trusted only while nothing they depend on has moved.
     *
     * <p>Vanilla asks {@code Explosion.getSeenPercent} twice for every entity in
     * range -- once for damage, once for knockback -- and each ask is a grid of
     * raycasts from points on the entity's box to the centre, some thirty for a
     * block-sized entity. A column of TNT puts a dozen charges in range of every
     * blast, and those raycasts were a quarter of each explosion.
     *
     * <p>The answer depends on the entity's box and on the shapes of the blocks
     * between it and the centre, and on nothing else for an entity that is not
     * living: its collision context is its height, which the box already gives.
     * So each such entity is asked once, on the pool, when the entity list is
     * final, and each later ask gets that answer back only if the entity's box
     * is unchanged and no block in the level has changed since. Anything else --
     * a knockback event that places a block, an entity moved by the one hurt
     * before it -- asks vanilla, live, as it always did.
     */
    public static final class Exposure {
        private final Entity[] entities;
        private final AABB[] boxes;
        private final float[] seen;
        private final long epoch;
        private final Vec3 centre;

        private Exposure(Entity[] entities, AABB[] boxes, float[] seen, long epoch, Vec3 centre) {
            this.entities = entities;
            this.boxes = boxes;
            this.seen = seen;
            this.epoch = epoch;
            this.centre = centre;
        }

        /** The precomputed answer if it still holds, or NaN to ask vanilla. */
        public float lookup(Level level, Vec3 centre, Entity entity) {
            if (((SkeinLevel) level).skein$blockEpoch() != this.epoch || !centre.equals(this.centre)) {
                return Float.NaN;
            }
            for (int i = 0; i < this.entities.length; i++) {
                if (this.entities[i] == entity) {
                    return entity.getBoundingBox().equals(this.boxes[i]) ? this.seen[i] : Float.NaN;
                }
            }
            return Float.NaN;
        }
    }

    /**
     * Answers exposure for the non-living entities in {@code nearby}, on the
     * pool when there are several. Raycasts only read, so answering for an
     * entity vanilla then skips costs time, not correctness.
     */
    public static Exposure exposure(Explosion explosion, Level level, Vec3 centre, List<Entity> nearby) {
        // As for the rays: a raycast from an entity to the centre reads the
        // chunks between them, and the helpers may not load one.
        int minX = Mth.floor(centre.x);
        int minZ = Mth.floor(centre.z);
        int maxX = minX;
        int maxZ = minZ;
        for (int i = 0; i < nearby.size(); i++) {
            AABB box = nearby.get(i).getBoundingBox();
            minX = Math.min(minX, Mth.floor(box.minX));
            minZ = Math.min(minZ, Mth.floor(box.minZ));
            maxX = Math.max(maxX, Mth.floor(box.maxX));
            maxZ = Math.max(maxZ, Mth.floor(box.maxZ));
        }
        if (!published(level, minX - 1, minZ - 1, maxX + 1, maxZ + 1)) {
            return null;
        }
        int count = 0;
        for (int i = 0; i < nearby.size(); i++) {
            Entity entity = nearby.get(i);
            if (!(entity instanceof LivingEntity)) count++;
        }
        Entity[] entities = new Entity[count];
        AABB[] boxes = new AABB[count];
        float[] seen = new float[count];
        for (int i = 0, n = 0; i < nearby.size(); i++) {
            Entity entity = nearby.get(i);
            if (!(entity instanceof LivingEntity)) {
                entities[n] = entity;
                boxes[n++] = entity.getBoundingBox();
            }
        }
        // Read before any raycast: a block that changes while they run moves the
        // epoch past this, and every answer is then refused.
        long epoch = ((SkeinLevel) level).skein$blockEpoch();
        if (count < 2) {
            for (int i = 0; i < count; i++) seen[i] = Explosion.getSeenPercent(centre, entities[i]);
            return new Exposure(entities, boxes, seen, epoch, centre);
        }
        DeferredBuffer[] parked = new DeferredBuffer[count];
        Skein.pool().invokeAll(count, index -> {
            DeferredBuffer buffer = new DeferredBuffer();
            parked[index] = buffer;
            TickContext.State previous = TickContext.enter(level, true, buffer);
            try {
                seen[index] = Explosion.getSeenPercent(centre, entities[index]);
            } finally {
                TickContext.exit(previous);
            }
        });
        DeferredBuffer own = TickContext.deferring() ? TickContext.buffer() : null;
        for (DeferredBuffer buffer : parked) {
            if (buffer == null || buffer.isEmpty()) continue;
            if (own != null) buffer.transferTo(own);
            else buffer.drain(level);
        }
        return new Exposure(entities, boxes, seen, epoch, centre);
    }

    /**
     * How far from the centre, in blocks, any ray of an explosion of this
     * radius can reach: a ray starts with at most 1.3 x radius of power, loses
     * at least 0.225 of it per 0.3-block step, and so stops within 1.73 x
     * radius; one more block for the step that lands on the edge.
     */
    static int rayReach(float radius) {
        return Mth.ceil(radius * 1.3F / 0.22500001F * 0.3F) + 1;
    }

    /**
     * Whether every chunk overlapping the block rectangle is loaded to FULL and
     * published -- exactly what a deferring thread's chunk lookup can answer
     * without asking for a load.
     */
    static boolean published(Level level, int minX, int minZ, int maxX, int maxZ) {
        if (!(level.getChunkSource() instanceof ServerChunkCache cache)) {
            return false;
        }
        ChunkMap map = cache.chunkMap;
        int full = ChunkLevel.byStatus(ChunkStatus.FULL);
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                ChunkHolder holder = map.getVisibleChunkIfPresent(ChunkPos.asLong(cx, cz));
                if (holder == null || holder.getTicketLevel() > full
                        || holder.getChunkIfPresent(ChunkStatus.FULL) == null) {
                    return false;
                }
            }
        }
        return true;
    }
}
