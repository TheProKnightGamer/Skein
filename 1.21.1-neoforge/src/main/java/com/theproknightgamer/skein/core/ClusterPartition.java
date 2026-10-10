package com.theproknightgamer.skein.core;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.ArrayList;
import java.util.List;

/**
 * A split by proximity rather than by grid: work is grouped into connected
 * components, where anything within {@code linkDistance} of anything else in a
 * group is in that group.
 *
 * <p>{@link Partition} exists because it parallelises well. This exists because
 * it is the only split that preserves order.
 *
 * <p>A grid assigns by position, so two things a few blocks apart can land
 * either side of a cell edge and end up in different passes -- separated by a
 * barrier, which is safe, but run in an order that is not the one vanilla would
 * have used. For entities and crops that does not matter. For scheduled ticks it
 * is the whole game: two redstone components are ordered by the sub-tick counter
 * they were scheduled with, and a contraption notices if that changes.
 *
 * <p>Connected components have the property a grid does not: <em>anything two
 * groups contain is further apart than the link distance</em>, so no pair split
 * across groups can interact, and every pair that can interact is inside one
 * group in vanilla's own order. The price is that parallelism now depends on how
 * spread out the work is. One lake of flowing water is one component and runs
 * exactly as it does in vanilla; forty separate farms are forty components.
 *
 * <p>Connectivity is decided between {@code linkDistance}-sized buckets, but
 * not by bucket adjacency: two points in adjacent buckets can be nearly two
 * bucket widths apart, so adjacency merges things that are comfortably out of
 * reach of each other. Measured, that is not a small effect -- thirty-six
 * separate redstone sites ninety-six blocks apart, with a link distance of
 * eighty, collapsed into four components, because each site was adjacent to the
 * next and adjacency is transitive.
 *
 * <p>Connectivity is horizontal. A chunk is a column, and two ticks in one
 * column write to the same chunk's heightmaps, block-entity map and ticker map
 * however far apart they are vertically -- none of which vanilla synchronises.
 * A vertical gap therefore buys no parallelism that is safe to take, so height
 * is left out of both the buckets and the test and a column is never split.
 *
 * <p>So each bucket keeps the bounding box of the points actually in it, and
 * two buckets are joined only if those boxes come within the link distance.
 * That is still conservative -- a box is not a point set -- but it is bounded by
 * how far apart the work really is rather than by where the grid lines fell.
 * The test is done once per bucket pair after every point has been filed, so a
 * box that grows later cannot be missed.
 */
public final class ClusterPartition<T> {

    /** One connected component: the work in it, in vanilla order, and the writes it parked. */
    public static final class Group<T> {
        final List<T> items = new ArrayList<>();
        final DeferredBuffer deferred = new DeferredBuffer();

        public List<T> items() {
            return this.items;
        }

        public DeferredBuffer deferred() {
            return this.deferred;
        }
    }

    private final List<T> items = new ArrayList<>();
    private final IntArrayList itemNodes = new IntArrayList();
    private final Long2IntMap bucketNodes = new Long2IntOpenHashMap();
    /** Negative component size at roots, parent index elsewhere. */
    private final IntArrayList parent = new IntArrayList();
    private final LongArrayList nodeKeys = new LongArrayList();
    /** Per node, the bounding box of the points filed into it. */
    private int[] minX = new int[64];
    private int[] minZ = new int[64];
    private int[] maxX = new int[64];
    private int[] maxZ = new int[64];
    private int[] rootGroups = new int[64];
    private int[] nodeGroups = new int[64];
    private final List<Group<T>> groups = new ArrayList<>();
    private final List<Group<T>> spare = new ArrayList<>();

    private int link = 80;

    public ClusterPartition() {
        this.bucketNodes.defaultReturnValue(-1);
    }

    public void begin(int linkDistance) {
        this.link = Math.max(1, linkDistance);
        for (Group<T> group : this.groups) {
            group.items.clear();
            this.spare.add(group);
        }
        this.groups.clear();
        this.items.clear();
        this.itemNodes.clear();
        this.bucketNodes.clear();
        this.parent.clear();
        this.nodeKeys.clear();
    }

    /** Files one piece of work at a position. Joining happens in {@link #finish()}. */
    public void add(T item, int x, int z) {
        long key = bucket(x, z);
        int node = node(key);
        if (x < this.minX[node]) this.minX[node] = x;
        if (z < this.minZ[node]) this.minZ[node] = z;
        if (x > this.maxX[node]) this.maxX[node] = x;
        if (z > this.maxZ[node]) this.maxZ[node] = z;
        this.items.add(item);
        this.itemNodes.add(node);
    }

    public int size() {
        return this.items.size();
    }

    /**
     * Resolves the components. Items are appended in the order they were added,
     * so each group holds vanilla's own relative order.
     */
    public void finish() {
        joinNeighbours();
        // Nodes were created in first-occurrence order, so resolving once per
        // node preserves group order without hashing/finding every item again.
        for (int node = 0; node < this.parent.size(); node++) {
            int root = find(node);
            int groupIndex = this.rootGroups[root];
            if (groupIndex < 0) {
                Group<T> group = this.spare.isEmpty() ? new Group<>() : this.spare.remove(this.spare.size() - 1);
                groupIndex = this.groups.size();
                this.rootGroups[root] = groupIndex;
                this.groups.add(group);
            }
            this.nodeGroups[node] = groupIndex;
        }
        for (int i = 0; i < this.items.size(); i++) {
            this.groups.get(this.nodeGroups[this.itemNodes.getInt(i)]).items.add(this.items.get(i));
        }
    }

    public List<Group<T>> groups() {
        return this.groups;
    }

    public void release() {
        for (Group<T> group : this.groups) {
            group.items.clear();
            group.deferred.clear();
        }
        this.items.clear();
        this.itemNodes.clear();
    }

    private int node(long key) {
        int existing = this.bucketNodes.get(key);
        if (existing >= 0) {
            return existing;
        }
        int id = this.parent.size();
        this.parent.add(-1);
        this.nodeKeys.add(key);
        this.bucketNodes.put(key, id);
        if (id >= this.minX.length) {
            grow(id + 1);
        }
        this.minX[id] = Integer.MAX_VALUE;
        this.minZ[id] = Integer.MAX_VALUE;
        this.maxX[id] = Integer.MIN_VALUE;
        this.maxZ[id] = Integer.MIN_VALUE;
        this.rootGroups[id] = -1;
        return id;
    }

    private void grow(int needed) {
        int capacity = Math.max(needed, this.minX.length * 2);
        this.minX = java.util.Arrays.copyOf(this.minX, capacity);
        this.minZ = java.util.Arrays.copyOf(this.minZ, capacity);
        this.maxX = java.util.Arrays.copyOf(this.maxX, capacity);
        this.maxZ = java.util.Arrays.copyOf(this.maxZ, capacity);
        this.rootGroups = java.util.Arrays.copyOf(this.rootGroups, capacity);
        this.nodeGroups = java.util.Arrays.copyOf(this.nodeGroups, capacity);
    }

    /**
     * Joins buckets whose contents actually come within the link distance. Run
     * once every point is filed, so every bounding box is final.
     */
    private void joinNeighbours() {
        for (int id = 0; id < this.nodeKeys.size(); id++) {
            long key = this.nodeKeys.getLong(id);
            int bx = (int) (key >> 32);
            int bz = (int) key;
            // Only one half of the neighbourhood: every undirected pair is
            // visited once, with four hash probes instead of eight.
            join(id, bx - 1, bz - 1);
            join(id, bx - 1, bz);
            join(id, bx - 1, bz + 1);
            join(id, bx, bz - 1);
        }
    }

    private void join(int id, int x, int z) {
        int other = this.bucketNodes.get(pack(x, z));
        if (other >= 0 && withinLink(id, other)) {
            union(id, other);
        }
    }

    private boolean withinLink(int a, int b) {
        return gap(this.minX[a], this.maxX[a], this.minX[b], this.maxX[b]) <= this.link
                && gap(this.minZ[a], this.maxZ[a], this.minZ[b], this.maxZ[b]) <= this.link;
    }

    private static long gap(int minA, int maxA, int minB, int maxB) {
        if (minB > maxA) {
            return (long) minB - maxA;
        }
        if (minA > maxB) {
            return (long) minA - maxB;
        }
        return 0;
    }

    private int find(int node) {
        int root = node;
        while (this.parent.getInt(root) >= 0) {
            root = this.parent.getInt(root);
        }
        while (node != root) {
            int next = this.parent.getInt(node);
            this.parent.set(node, root);
            node = next;
        }
        return root;
    }

    private void union(int a, int b) {
        int rootA = find(a);
        int rootB = find(b);
        if (rootA != rootB) {
            // Attach the smaller tree to the larger one. Negative root values
            // hold sizes, so the more negative value is the larger component.
            if (this.parent.getInt(rootA) > this.parent.getInt(rootB)) {
                int swap = rootA;
                rootA = rootB;
                rootB = swap;
            }
            this.parent.set(rootA, this.parent.getInt(rootA) + this.parent.getInt(rootB));
            this.parent.set(rootB, rootA);
        }
    }

    private long bucket(int x, int z) {
        return pack(Math.floorDiv(x, this.link), Math.floorDiv(z, this.link));
    }

    private static long pack(int x, int z) {
        return (long) x << 32 | z & 0xffffffffL;
    }
}
