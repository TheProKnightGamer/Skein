package com.theproknightgamer.skein.core;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A reusable split of one level's work into cells that are safe to run at the
 * same time, grouped into the four passes of {@link Grid}.
 *
 * <p>The same shape serves entities, random-ticked chunks and block entities:
 * each of them is a pile of things at known positions, and each of them needs
 * the pile turned into groups where nothing in one group can reach anything in
 * another. What differs is only how a member's footprint is measured, which the
 * caller passes in.
 *
 * <p>The buffers are kept from tick to tick and the cells are recycled through a
 * free list, so a steady-state tick allocates nothing here.
 */
public final class Partition<T> {

    public static final int PASSES = 4;

    /** One cell of one grid: the work in it, and the writes that work parked. */
    public static final class Cell<T> {
        long key;
        final ObjectArrayList<T> items = new ObjectArrayList<>();
        final DeferredBuffer deferred = new DeferredBuffer();

        public List<T> items() {
            return this.items;
        }

        public DeferredBuffer deferred() {
            return this.deferred;
        }
    }

    /** Largest cells first so the longest task starts earliest; key breaks ties so the split is reproducible. */
    private final Comparator<Cell<T>> longestFirst =
            Comparator.<Cell<T>>comparingInt(cell -> -cell.items.size()).thenComparingLong(cell -> cell.key);

    private final List<Long2ObjectMap<Cell<T>>> index = new ArrayList<>(PASSES);
    private final List<ObjectArrayList<Cell<T>>> passes = new ArrayList<>(PASSES);
    private final List<Cell<T>> spare = new ArrayList<>();
    private final ObjectArrayList<T> serial = new ObjectArrayList<>();
    private final List<T> all = new ArrayList<>();

    private Grid grid = new Grid(128, 32);

    public Partition() {
        for (int i = 0; i < PASSES; i++) {
            this.passes.add(new ObjectArrayList<>());
            this.index.add(new Long2ObjectOpenHashMap<>());
        }
    }

    public void begin(Grid grid) {
        this.grid = grid;
        for (List<Cell<T>> cells : this.passes) {
            for (Cell<T> cell : cells) {
                recycle(cell);
            }
            cells.clear();
        }
        for (Long2ObjectMap<Cell<T>> map : this.index) {
            map.clear();
        }
        this.serial.clear();
        this.all.clear();
    }

    /**
     * Files one member by its footprint, or into the serial set if no grid holds
     * it -- which happens to a footprint wider than the stretch where the two
     * offsets of an axis hand over, such as a mob that reaches much further than
     * the margin, and otherwise only when the configured cell is smaller than
     * four margins, which the config clamp rules out.
     */
    public void place(T item, double minX, double maxX, double minZ, double maxZ) {
        this.all.add(item);
        long cellX = this.grid.locate(minX, maxX);
        long cellZ = this.grid.locate(minZ, maxZ);
        if (cellX == Grid.NO_CELL || cellZ == Grid.NO_CELL) {
            this.serial.add(item);
            return;
        }
        long key = ChunkPos.asLong((int) (cellX >> 1), (int) (cellZ >> 1));
        cellFor(Grid.pass((int) (cellX & 1), (int) (cellZ & 1)), key).items.add(item);
    }

    /**
     * Files one member without placing it in a cell, for a caller that will
     * split the pile another way and place only what is left ({@link #all}).
     */
    public void file(T item) {
        this.all.add(item);
    }

    /** Files one member that must run on the level-owning thread whatever its position. */
    public void pin(T item) {
        this.all.add(item);
        this.serial.add(item);
    }

    /** Everything filed, in the order it arrived. */
    public List<T> all() {
        return this.all;
    }

    /** What must run on the level-owning thread, after every pass. */
    public List<T> serial() {
        return this.serial;
    }

    public List<Cell<T>> pass(int index) {
        return this.passes.get(index);
    }

    /** Sorts each pass and returns how many members ended up in parallel cells. */
    public int sort() {
        int parallel = 0;
        for (ObjectArrayList<Cell<T>> cells : this.passes) {
            // Key breaks every tie, so an in-place unstable sort produces the
            // same deterministic order without TimSort's per-pass work arrays.
            cells.unstableSort(this.longestFirst);
            for (Cell<T> cell : cells) {
                parallel += cell.items.size();
            }
        }
        return parallel;
    }

    /** Moves a whole pass into the serial set, for when it is too small to be worth a barrier. */
    public void collapse(int pass) {
        List<Cell<T>> cells = this.passes.get(pass);
        for (Cell<T> cell : cells) {
            this.serial.addAll(cell.items);
            recycle(cell);
        }
        cells.clear();
    }

    /**
     * Drops every reference so a tick's dead work is not held until the next
     * one, and discards anything a pass parked but never got to apply -- which
     * only happens on the way out of a crashing tick, and must not be replayed
     * into the next one.
     */
    public void release() {
        for (List<Cell<T>> cells : this.passes) {
            for (Cell<T> cell : cells) {
                cell.items.clear();
                cell.deferred.clear();
            }
        }
        this.serial.clear();
        this.all.clear();
    }

    private Cell<T> cellFor(int pass, long key) {
        Long2ObjectMap<Cell<T>> map = this.index.get(pass);
        Cell<T> cell = map.get(key);
        if (cell == null) {
            cell = this.spare.isEmpty() ? new Cell<>() : this.spare.remove(this.spare.size() - 1);
            cell.key = key;
            map.put(key, cell);
            this.passes.get(pass).add(cell);
        }
        return cell;
    }

    private void recycle(Cell<T> cell) {
        cell.items.clear();
        this.spare.add(cell);
    }
}
