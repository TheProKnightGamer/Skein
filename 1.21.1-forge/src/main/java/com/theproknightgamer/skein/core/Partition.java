package com.theproknightgamer.skein.core;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Arrays;
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
 *
 * <h2>Wide members</h2>
 *
 * <p>A member whose footprint is too wide for the grid's cells -- a villager,
 * bee or blaze, whose follow range reaches sixteen blocks past the margin -- is
 * interior to one of the four offsets over only part of each axis: a villager
 * over half of it, so over a quarter of the plane. The rest used to run
 * serially after every pass, and a village is mostly villagers: measured on
 * 1440 penned animals with a villager in each pen, that serial tail of about
 * 110 villagers took longer than all the parallel cells together.
 *
 * <p>Those members are now filed again, once everything else has been, into
 * four more passes over a grid whose cells are wide enough to hold them -- the
 * same geometry and the same guarantee at a coarser size, so nothing in one of
 * its cells reaches another of the same pass. The cell size is chosen per tick
 * from the members that need it: wide enough for seven in eight of them
 * wherever they stand. Whatever the wide grid cannot hold still runs serially.
 *
 * <p>Only a footprint the ordinary grid could already hold somewhere -- one
 * narrower than a cell less two margins -- is admitted. Such a member already
 * ticks on a worker whenever it stands well inside a cell, so the wide grid
 * changes how often that happens, not whether: its reach is the reach the
 * loaded margin and every other rule were sized for. An enderman, a ghast, a
 * large contraption or ship has never ticked anywhere but the level thread,
 * reads further than the chunks a worker is guaranteed, and stays there.
 */
public final class Partition<T> {

    /** The grid's four passes, then the wide grid's four. */
    public static final int PASSES = 8;
    /** The first pass of the wide grid. */
    public static final int WIDE_PASS = 4;
    /** Fewer wide members than this are not worth four more barriers; they run serially. */
    static final int MIN_WIDE = 8;
    /** The widest the wide grid's cells may be, in cells of the ordinary grid. */
    private static final int MAX_WIDE_REGIONS = 4;

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
    /** Members no cell of the grid holds, waiting for the wide grid to be sized. */
    private final ObjectArrayList<T> wide = new ObjectArrayList<>();
    /** Their footprints, four values each: minX, maxX, minZ, maxZ. */
    private double[] wideSpans = new double[64];
    private double[] wideWidths = new double[16];

    private Grid grid = new Grid(128, 32);
    private Grid wideGrid = new Grid(256, 32);

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
        this.wide.clear();
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
            int at = this.wide.size() * 4;
            if (at + 4 > this.wideSpans.length) {
                this.wideSpans = Arrays.copyOf(this.wideSpans, this.wideSpans.length * 2);
            }
            this.wideSpans[at] = minX;
            this.wideSpans[at + 1] = maxX;
            this.wideSpans[at + 2] = minZ;
            this.wideSpans[at + 3] = maxZ;
            this.wide.add(item);
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

    /**
     * Files the members the grid could not hold into the wide grid's passes, or
     * the serial set; see the class comment. Called once everything has been
     * placed, before the passes run.
     */
    public void fileWide() {
        int total = this.wide.size();
        if (total == 0) {
            return;
        }
        if (this.wideWidths.length < total) {
            this.wideWidths = new double[Math.max(total, this.wideWidths.length * 2)];
        }
        // Admit only what the ordinary grid could hold somewhere (see the class
        // comment), compacting in place; the rest keeps to the serial set. The
        // comparison is written so that a NaN width is refused too.
        double admit = this.grid.regionBlocks() - 2.0 * this.grid.margin();
        int count = 0;
        for (int i = 0; i < total; i++) {
            int from = i * 4;
            double width = Math.max(this.wideSpans[from + 1] - this.wideSpans[from],
                    this.wideSpans[from + 3] - this.wideSpans[from + 2]);
            T item = this.wide.get(i);
            if (!(width < admit)) {
                this.serial.add(item);
                continue;
            }
            int to = count * 4;
            if (to != from) {
                System.arraycopy(this.wideSpans, from, this.wideSpans, to, 4);
            }
            this.wide.set(count, item);
            this.wideWidths[count] = width;
            count++;
        }
        this.wide.size(count);
        if (count < MIN_WIDE) {
            this.serial.addAll(this.wide);
            this.wide.clear();
            return;
        }
        Arrays.sort(this.wideWidths, 0, count);
        double width = this.wideWidths[(count - 1) * 7 / 8];
        // Per axis, a span is interior to one of the two offsets everywhere once
        // the cell is four margins plus twice its width; in whole pairs of chunks,
        // so the shifted offset still falls on a chunk border.
        int margin = this.grid.margin();
        double needed = 4.0 * margin + 2.0 * width;
        int limit = this.grid.regionBlocks() * MAX_WIDE_REGIONS;
        int blocks = needed >= limit ? limit : Math.min(limit, 32 * (int) Math.ceil(needed / 32.0));
        blocks = Math.max(blocks, this.grid.regionBlocks() + 32);
        if (this.wideGrid.regionBlocks() != blocks || this.wideGrid.margin() != margin) {
            this.wideGrid = new Grid(blocks, margin);
        }
        for (int i = 0; i < count; i++) {
            int at = i * 4;
            T item = this.wide.get(i);
            long cellX = this.wideGrid.locate(this.wideSpans[at], this.wideSpans[at + 1]);
            long cellZ = this.wideGrid.locate(this.wideSpans[at + 2], this.wideSpans[at + 3]);
            if (cellX == Grid.NO_CELL || cellZ == Grid.NO_CELL) {
                this.serial.add(item);
                continue;
            }
            long key = ChunkPos.asLong((int) (cellX >> 1), (int) (cellZ >> 1));
            cellFor(WIDE_PASS + Grid.pass((int) (cellX & 1), (int) (cellZ & 1)), key).items.add(item);
        }
        this.wide.clear();
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
        this.wide.clear();
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
