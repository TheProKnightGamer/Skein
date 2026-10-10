package com.theproknightgamer.skein.core;

import net.minecraft.world.entity.Entity;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.List;
import java.util.function.ToDoubleFunction;

import static com.theproknightgamer.skein.core.SpatialBounds.*;

/**
 * Turns a pile of entities into precedence levels, so that a crowd sharing one
 * cell -- or one bubble column -- can still be spread across the pool.
 *
 * <h2>Why a cell is not enough</h2>
 *
 * <p>{@link Grid} is a <em>conservative</em> conflict test. It assumes any two
 * entities in the same cell may reach each other and therefore runs the whole
 * cell on one thread. That assumption is what makes the geometry cheap, and it
 * is also its ceiling: a cell is keyed on {@code (x, z)}, so every entity
 * standing on one column shares a key at every cell size, forever. Shrinking
 * cells separates different columns and can never separate one. Five thousand
 * projectiles over a farm smaller than a cell get exactly one thread, and no
 * amount of tuning changes that.
 *
 * <p>The test the geometry stands in for is simply <em>can these two entities
 * reach each other</em>. Applied directly it is far more permissive: two
 * snowballs a metre apart conflict, two snowballs ten metres apart do not, and
 * whether they share a cell has nothing to do with it.
 *
 * <h2>What is computed</h2>
 *
 * <p>Walking the list in vanilla's own order, each entity is given
 *
 * <pre>{@code level[i] = 1 + max{ level[j] : j < i and i conflicts with j }}</pre>
 *
 * <p>which yields two properties, and the whole correctness argument is these
 * two lines:
 *
 * <ul>
 *   <li>Two entities on the <em>same</em> level never conflict -- if they did,
 *       the later one would have been pushed past the earlier one. So they may
 *       run at the same time on different threads.</li>
 *   <li>Two entities that <em>do</em> conflict always land on different levels,
 *       in list order, and levels run one after another. So they execute in the
 *       order vanilla would have run them.</li>
 * </ul>
 *
 * <p>Every pair therefore either behaves exactly as it does serially, or is a
 * pair that provably cannot observe each other. That is the same bargain the
 * grid already makes -- Skein has always let two entities in different cells
 * tick concurrently -- but stated over the entities that are actually close
 * rather than over a square drawn around them.
 *
 * <h2>The one thing that has to be true</h2>
 *
 * <p>{@code reach} must bound everything one clustered entity's tick can touch,
 * <em>including how far it moves while ticking</em>, because an entity that
 * moves during its own tick can end up beside something it did not conflict
 * with when the graph was built. It is checked against a list of admitted types
 * rather than guessed at, and the game event -- whose sixteen-block radius
 * would otherwise make every entity in a farm conflict with every other -- is
 * parked until the barrier by {@code ServerLevelMixin}.
 *
 * <p>Conflict is tested as a Chebyshev distance, which is a superset of the
 * sphere the reach describes and costs three comparisons instead of a multiply.
 */
public final class EntityLevels {

    /** Above this the graph is too tangled to be worth the barriers; fall back to the grid. */
    private static final double MAX_LEVEL_FRACTION = 0.25;

    private double[] px = new double[1024];
    private double[] py = new double[1024];
    private double[] pz = new double[1024];
    private int[] level = new int[1024];
    private double[] reach = new double[1024];
    private int[] next = new int[1024];
    /** Maximum precedence in each immutable suffix of a bucket's insertion chain. */
    private int[] suffixMax = new int[1024];
    /** Second chain through the same buckets, holding only entities with a reach above zero. */
    private int[] nextPositive = new int[1024];
    private int[] order = new int[1024];
    private int[] levelStart = new int[64];
    private int[] counts = new int[64];

    /** Open-addressed bucket table: parallel key/head arrays, power-of-two sized. */
    private long[] slotKey = new long[2048];
    private int[] slotHead = new int[2048];
    private int[] slotHeadPositive = new int[2048];
    private int mask = 2047;

    /** Positive entities indexed into every bucket their reach intersects. */
    private final Long2IntOpenHashMap influenceHeads = new Long2IntOpenHashMap();
    private final IntArrayList influenceEntities = new IntArrayList();
    private final IntArrayList influenceNext = new IntArrayList();
    private final IntArrayList influenceMax = new IntArrayList();

    public EntityLevels() {
        this.influenceHeads.defaultReturnValue(-1);
    }

    private int size;
    private int levels;

    /**
     * Builds the levels for {@code items}.
     *
     * @return the number of levels, or {@code -1} when the graph is too dense
     *         for levelling to buy anything over running the list serially
     */
    public int build(List<Entity> items, ToDoubleFunction<Entity> reachOf) {
        return build(items, reachOf, Integer.MAX_VALUE);
    }

    /** Also stops as soon as the caller's minimum useful level width cannot be met. */
    public int build(List<Entity> items, ToDoubleFunction<Entity> reachOf, int maxLevels) {
        this.size = items.size();
        this.levels = 0;
        int limit = Math.min(maxLevels, Math.max(4, (int) (this.size * MAX_LEVEL_FRACTION)));
        if (this.size == 0 || limit < 1) {
            return -1;
        }
        grow(this.size);

        double widest = 0.0;
        for (int i = 0; i < this.size; i++) {
            double reach = reachOf.applyAsDouble(items.get(i));
            this.reach[i] = reach;
            if (reach > widest) {
                widest = reach;
            }
        }

        if (widest <= 0.0) {
            // Nothing in the pile can write to anything else in it -- the usual
            // case, because a projectile can only hit what is pickable. There
            // are no edges to find, so there is one level and it is everybody,
            // in the order it arrived.
            for (int i = 0; i < this.size; i++) {
                this.order[i] = i;
            }
            this.levelStart[0] = 0;
            this.levelStart[1] = this.size;
            this.levels = 1;
            return 1;
        }

        // Twice the widest reach lets a query inspect two buckets per axis
        // in the usual case, rather than three. Exact distance tests still
        // decide conflicts; outward rounding includes floating-point boundaries.
        int positive = 0;
        int influenceLimit = this.size / 4;
        for (int i = 0; i < this.size && positive < influenceLimit; i++) {
            if (this.reach[i] > 0.0) positive++;
        }
        double bucket = widest * 2.0;
        boolean useInfluence = positive < influenceLimit;
        clearSlots();
        if (useInfluence) {
            this.influenceHeads.clear();
            this.influenceEntities.clear();
            this.influenceNext.clear();
            this.influenceMax.clear();
        }

        int count = useInfluence ? buildInfluence(items, bucket, limit) : buildStandard(items, widest, bucket, limit);
        if (count < 0) return -1;
        this.levels = count;
        sortByLevel();
        return count;
    }

    /** Indices into the list passed to {@link #build}, grouped by level, list order preserved inside each. */
    public int[] order() {
        return this.order;
    }

    /** {@code order[levelStart[l] .. levelStart[l + 1])} is level {@code l}. */
    public int[] levelStart() {
        return this.levelStart;
    }

    public int levelCount() {
        return this.levels;
    }

    /** Counting sort: stable, so entities keep vanilla's relative order inside a level. */
    private void sortByLevel() {
        if (this.counts.length < this.levels + 1) {
            this.counts = new int[this.levels + 1];
            this.levelStart = new int[this.levels + 2];
        }
        java.util.Arrays.fill(this.counts, 0, this.levels + 1, 0);
        for (int i = 0; i < this.size; i++) {
            this.counts[this.level[i]]++;
        }
        int running = 0;
        for (int l = 0; l < this.levels; l++) {
            this.levelStart[l] = running;
            running += this.counts[l];
            this.counts[l] = this.levelStart[l];
        }
        this.levelStart[this.levels] = running;
        for (int i = 0; i < this.size; i++) {
            this.order[this.counts[this.level[i]]++] = i;
        }
    }

    // Keep the two strategies in separate loops so HotSpot can optimize them
    // independently when levels with different entity mixes share a server.
    private int buildStandard(List<Entity> items, double widest, double bucket, int limit) {
        int highest = 0;
        for (int i = 0; i < this.size; i++) {
            Entity entity = items.get(i);
            double x = entity.getX(), y = entity.getY(), z = entity.getZ();
            this.px[i] = x;
            this.py[i] = y;
            this.pz[i] = z;
            int bx = floor(x / bucket), by = floor(y / bucket), bz = floor(z / bucket);
            long centerKey = key(bx, by, bz);
            double own = this.reach[i];
            int assigned = 0;
            int center = slotOf(centerKey);
            if (center >= 0) assigned = scanStandard(center, x, y, z, own, assigned, highest);
            if (assigned <= highest) {
                int lowX = lower(x, widest, bucket), highX = upper(x, widest, bucket);
                int lowY = lower(y, widest, bucket), highY = upper(y, widest, bucket);
                int lowZ = lower(z, widest, bucket), highZ = upper(z, widest, bucket);
                neighbours:
                for (long qx = lowX; qx <= highX; qx++) {
                    for (long qy = lowY; qy <= highY; qy++) {
                        for (long qz = lowZ; qz <= highZ; qz++) {
                            if (qx == bx && qy == by && qz == bz) continue;
                            int slot = slotOf(key((int) qx, (int) qy, (int) qz));
                            if (slot < 0) continue;
                            assigned = scanStandard(slot, x, y, z, own, assigned, highest);
                            if (assigned > highest) break neighbours;
                        }
                    }
                }
            }
            if (assigned >= limit) return -1;
            this.level[i] = assigned;
            highest = Math.max(highest, assigned);
            insert(i, centerKey, own > 0.0, highest);
        }
        return highest + 1;
    }

    /**
     * A pair conflicts if either entity reaches the other. For a population
     * dominated by zero-reach projectiles, index each positive predecessor's
     * reach into covered buckets: an incoming query then probes just one bucket.
     * Only positive current entities need an additional outgoing range query.
     */
    private int buildInfluence(List<Entity> items, double bucket, int limit) {
        int highest = 0;
        for (int i = 0; i < this.size; i++) {
            Entity entity = items.get(i);
            double x = entity.getX(), y = entity.getY(), z = entity.getZ();
            this.px[i] = x;
            this.py[i] = y;
            this.pz[i] = z;
            int bx = floor(x / bucket), by = floor(y / bucket), bz = floor(z / bucket);
            long centerKey = key(bx, by, bz);
            double own = this.reach[i];
            int assigned = incoming(centerKey, x, y, z, highest);
            if (own > 0.0 && assigned <= highest) {
                int center = slotOf(centerKey);
                if (center >= 0) assigned = scanOutgoing(center, x, y, z, own, assigned, highest);
                if (assigned <= highest) {
                    int lowX = lower(x, own, bucket), highX = upper(x, own, bucket);
                    int lowY = lower(y, own, bucket), highY = upper(y, own, bucket);
                    int lowZ = lower(z, own, bucket), highZ = upper(z, own, bucket);
                    neighbours:
                    for (long qx = lowX; qx <= highX; qx++) {
                        for (long qy = lowY; qy <= highY; qy++) {
                            for (long qz = lowZ; qz <= highZ; qz++) {
                                if (qx == bx && qy == by && qz == bz) continue;
                                int slot = slotOf(key((int) qx, (int) qy, (int) qz));
                                if (slot < 0) continue;
                                assigned = scanOutgoing(slot, x, y, z, own, assigned, highest);
                                if (assigned > highest) break neighbours;
                            }
                        }
                    }
                }
            }
            if (assigned >= limit) return -1;
            this.level[i] = assigned;
            highest = Math.max(highest, assigned);
            insert(i, centerKey, false, highest);
            if (own > 0.0) cover(i, x, y, z, own, bucket);
        }
        return highest + 1;
    }

    private int scanStandard(int slot, double x, double y, double z, double own, int assigned, int highest) {
        int head = own > 0.0 ? this.slotHead[slot] : this.slotHeadPositive[slot];
        int[] chain = own > 0.0 ? this.next : this.nextPositive;
        for (int j = head; j >= 0; j = chain[j]) {
            if (this.level[j] < assigned) {
                if (this.suffixMax[j] < assigned) break;
                continue;
            }
            double radius = Math.max(own, this.reach[j]);
            if (Math.abs(this.px[j] - x) <= radius
                    && Math.abs(this.py[j] - y) <= radius && Math.abs(this.pz[j] - z) <= radius) {
                assigned = this.level[j] + 1;
                if (assigned > highest) break;
            }
        }
        return assigned;
    }

    /** Outgoing conflicts use the current entity's radius, regardless of the predecessor's reach. */
    private int scanOutgoing(int slot, double x, double y, double z, double radius, int assigned, int highest) {
        for (int j = this.slotHead[slot]; j >= 0; j = this.next[j]) {
            if (this.level[j] < assigned) {
                if (this.suffixMax[j] < assigned) break;
                continue;
            }
            if (Math.abs(this.px[j] - x) <= radius
                    && Math.abs(this.py[j] - y) <= radius && Math.abs(this.pz[j] - z) <= radius) {
                assigned = this.level[j] + 1;
                if (assigned > highest) break;
            }
        }
        return assigned;
    }

    /** Highest incoming conflict from a positive-reach predecessor. */
    private int incoming(long key, double x, double y, double z, int highest) {
        int assigned = 0;
        for (int entry = this.influenceHeads.get(key); entry >= 0; entry = this.influenceNext.getInt(entry)) {
            int j = this.influenceEntities.getInt(entry);
            if (this.level[j] < assigned) {
                if (this.influenceMax.getInt(entry) < assigned) break;
                continue;
            }
            double radius = this.reach[j];
            if (Math.abs(this.px[j] - x) <= radius
                    && Math.abs(this.py[j] - y) <= radius && Math.abs(this.pz[j] - z) <= radius) {
                assigned = this.level[j] + 1;
                if (assigned > highest) break;
            }
        }
        return assigned;
    }

    private void cover(int index, double x, double y, double z, double reach, double bucket) {
        int lowX = lower(x, reach, bucket), highX = upper(x, reach, bucket);
        int lowY = lower(y, reach, bucket), highY = upper(y, reach, bucket);
        int lowZ = lower(z, reach, bucket), highZ = upper(z, reach, bucket);
        for (long qx = lowX; qx <= highX; qx++) {
            for (long qy = lowY; qy <= highY; qy++) {
                for (long qz = lowZ; qz <= highZ; qz++) {
                    int entry = this.influenceEntities.size();
                    int previous = this.influenceHeads.put(key((int) qx, (int) qy, (int) qz), entry);
                    this.influenceEntities.add(index);
                    this.influenceNext.add(previous);
                    this.influenceMax.add(previous < 0 ? this.level[index]
                            : Math.max(this.level[index], this.influenceMax.getInt(previous)));
                }
            }
        }
    }

    private static long key(int x, int y, int z) {
        // Cheap spread; collisions are resolved by the exact distance test
        // above, so a clash costs a few comparisons rather than correctness.
        return (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L);
    }

    /** The slot holding {@code key}, or -1. A claimed slot always has a non-negative head. */
    private int slotOf(long key) {
        int slot = (int) (key ^ (key >>> 32)) & this.mask;
        while (true) {
            if (this.slotHead[slot] < 0) {
                return -1;
            }
            if (this.slotKey[slot] == key) {
                return slot;
            }
            slot = (slot + 1) & this.mask;
        }
    }

    private void insert(int index, long key, boolean positiveReach, int highest) {
        int slot = (int) (key ^ (key >>> 32)) & this.mask;
        while (this.slotHead[slot] >= 0 && this.slotKey[slot] != key) {
            slot = (slot + 1) & this.mask;
        }
        this.slotKey[slot] = key;
        this.next[index] = this.slotHead[slot];
        this.suffixMax[index] = this.level[index] == highest || this.next[index] < 0 ? this.level[index]
                : Math.max(this.level[index], this.suffixMax[this.next[index]]);
        this.slotHead[slot] = index;
        if (positiveReach) {
            this.nextPositive[index] = this.slotHeadPositive[slot];
            this.slotHeadPositive[slot] = index;
        }
    }

    private void clearSlots() {
        int wanted = Integer.highestOneBit(Math.max(16, this.size * 2 - 1)) * 2;
        if (this.slotHead.length < wanted) {
            this.slotKey = new long[wanted];
            this.slotHead = new int[wanted];
            this.slotHeadPositive = new int[wanted];
        }
        this.mask = wanted - 1;
        java.util.Arrays.fill(this.slotHead, 0, wanted, -1);
        java.util.Arrays.fill(this.slotHeadPositive, 0, wanted, -1);
    }

    private void grow(int wanted) {
        if (this.px.length >= wanted) {
            return;
        }
        int capacity = Integer.highestOneBit(Math.max(1024, wanted - 1)) * 2;
        this.px = new double[capacity];
        this.py = new double[capacity];
        this.pz = new double[capacity];
        this.level = new int[capacity];
        this.reach = new double[capacity];
        this.next = new int[capacity];
        this.suffixMax = new int[capacity];
        this.nextPositive = new int[capacity];
        this.order = new int[capacity];
    }
}
