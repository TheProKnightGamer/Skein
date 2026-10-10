package com.theproknightgamer.skein.core;

import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.function.IntPredicate;

import static com.theproknightgamer.skein.core.SpatialBounds.*;

/**
 * A reusable "is anything near here" index over a list of entity positions.
 *
 * <p>Open-addressed and array-backed for the same reason
 * {@link EntityLevels} is: it is rebuilt from scratch every tick on the
 * coordinator with every worker parked, so it must not allocate.
 */
final class PointGrid {

    private static final int LINEAR_LIMIT = 8;

    private double[] px = new double[256];
    private double[] py = new double[256];
    private double[] pz = new double[256];
    private int[] next = new int[256];
    private long[] slotKey = new long[512];
    private int[] slotHead = new int[512];
    private int mask = 511;
    private double bucket = 1.0;
    private int size;
    private double minX, minY, minZ, maxX, maxY, maxZ;

    /** Indexes points into buckets twice as wide as the expected query reach. */
    void build(List<Entity> points, double reach) {
        this.size = points.size();
        this.bucket = Math.max(reach, 1.0e-3) * 2.0;
        this.minX = this.minY = this.minZ = Double.POSITIVE_INFINITY;
        this.maxX = this.maxY = this.maxZ = Double.NEGATIVE_INFINITY;
        grow(this.size);
        if (this.size > LINEAR_LIMIT) clearSlots();
        for (int i = 0; i < this.size; i++) {
            Entity entity = points.get(i);
            double x = entity.getX();
            double y = entity.getY();
            double z = entity.getZ();
            this.px[i] = x;
            this.py[i] = y;
            this.pz[i] = z;
            this.minX = Math.min(this.minX, x);
            this.minY = Math.min(this.minY, y);
            this.minZ = Math.min(this.minZ, z);
            this.maxX = Math.max(this.maxX, x);
            this.maxY = Math.max(this.maxY, y);
            this.maxZ = Math.max(this.maxZ, z);
            if (this.size > LINEAR_LIMIT) {
                insert(i, key(floor(x / this.bucket), floor(y / this.bucket), floor(z / this.bucket)));
            }
        }
    }

    boolean isEmpty() {
        return this.size == 0;
    }

    /** Whether any indexed point is within {@code reach} of {@code (x, y, z)}, Chebyshev. */
    boolean anyWithin(double x, double y, double z, double reach) {
        return anyWithin(x, y, z, reach, null);
    }

    /**
     * Whether any indexed point within {@code reach} of {@code (x, y, z)},
     * Chebyshev, also passes {@code test}, which is handed the point's index in
     * the list given to {@link #build}. A null test accepts every point.
     */
    boolean anyWithin(double x, double y, double z, double reach, IntPredicate test) {
        // Isolated farms often have just a few outsiders, all far away. Reject
        // those queries before probing empty buckets.
        if (this.size == 0 || this.minX - x > reach || x - this.maxX > reach
                || this.minY - y > reach || y - this.maxY > reach
                || this.minZ - z > reach || z - this.maxZ > reach) {
            return false;
        }
        return this.size <= LINEAR_LIMIT ? scanSmall(x, y, z, reach, test) : searchBuckets(x, y, z, reach, test);
    }

    private boolean scanSmall(double x, double y, double z, double reach, IntPredicate test) {
        for (int j = 0; j < this.size; j++) {
            if (Math.abs(this.px[j] - x) <= reach && Math.abs(this.py[j] - y) <= reach
                    && Math.abs(this.pz[j] - z) <= reach && (test == null || test.test(j))) return true;
        }
        return false;
    }

    /** Separate from the bounds rejection so the common fast path stays small enough to inline. */
    private boolean searchBuckets(double x, double y, double z, double reach, IntPredicate test) {
        int lowX = lower(x, reach, this.bucket), highX = upper(x, reach, this.bucket);
        int lowY = lower(y, reach, this.bucket), highY = upper(y, reach, this.bucket);
        int lowZ = lower(z, reach, this.bucket), highZ = upper(z, reach, this.bucket);
        // A query much wider than the buckets were sized for -- a box test
        // padded by some outsider's large hitbox -- would probe more empty
        // buckets than there are points to scan.
        if (((double) highX - lowX + 1) * ((double) highY - lowY + 1) * ((double) highZ - lowZ + 1) > this.size) {
            return scanSmall(x, y, z, reach, test);
        }
        for (long qx = lowX; qx <= highX; qx++) {
            for (long qy = lowY; qy <= highY; qy++) {
                for (long qz = lowZ; qz <= highZ; qz++) {
                    for (int j = head(key((int) qx, (int) qy, (int) qz)); j >= 0; j = this.next[j]) {
                        if (Math.abs(this.px[j] - x) <= reach
                                && Math.abs(this.py[j] - y) <= reach
                                && Math.abs(this.pz[j] - z) <= reach
                                && (test == null || test.test(j))) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static long key(int x, int y, int z) {
        return (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L);
    }

    private int head(long key) {
        int slot = (int) (key ^ (key >>> 32)) & this.mask;
        while (true) {
            int h = this.slotHead[slot];
            if (h < 0) {
                return -1;
            }
            if (this.slotKey[slot] == key) {
                return h;
            }
            slot = (slot + 1) & this.mask;
        }
    }

    private void insert(int index, long key) {
        int slot = (int) (key ^ (key >>> 32)) & this.mask;
        while (true) {
            int h = this.slotHead[slot];
            if (h < 0) {
                this.slotKey[slot] = key;
                this.slotHead[slot] = index;
                this.next[index] = -1;
                return;
            }
            if (this.slotKey[slot] == key) {
                this.next[index] = h;
                this.slotHead[slot] = index;
                return;
            }
            slot = (slot + 1) & this.mask;
        }
    }

    private void clearSlots() {
        int wanted = Integer.highestOneBit(Math.max(16, this.size * 2 - 1)) * 2;
        if (this.slotHead.length < wanted) {
            this.slotKey = new long[wanted];
            this.slotHead = new int[wanted];
        }
        this.mask = wanted - 1;
        java.util.Arrays.fill(this.slotHead, 0, wanted, -1);
    }

    private void grow(int wanted) {
        if (this.px.length >= wanted) {
            return;
        }
        int capacity = Integer.highestOneBit(Math.max(256, wanted - 1)) * 2;
        this.px = new double[capacity];
        this.py = new double[capacity];
        this.pz = new double[capacity];
        this.next = new int[capacity];
    }
}
