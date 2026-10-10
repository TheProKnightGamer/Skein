package com.theproknightgamer.skein.core;

/**
 * The four half-offset grids everything parallel in Skein is split by.
 *
 * <p>Lay one square grid over the world and the safe interior of a cell -- the
 * part at least {@code margin} from every edge -- is only {@code (R - 2m)^2} out
 * of {@code R^2}. At the defaults that is a quarter of the area, so three
 * quarters of whatever is being split falls out of the parallel set.
 *
 * <p>Lay four, offset from each other by half a cell in x, in z, and in both,
 * and every position is interior to exactly one of them. Ticking each grid's
 * share as its own pass, with a barrier between, covers everything.
 *
 * <p>That "exactly one" needs a cell at least four margins across, and a little
 * more than that for a span: per axis, a span is interior to the unshifted grid
 * over half the cell and to the shifted one over the other half only if the two
 * halves meet, and every block of the span's own width pushes them apart.
 * {@link com.theproknightgamer.skein.SkeinConfig#resolveRegionChunks} enforces it.
 *
 * <p>Everything is tested as a span rather than a point, because not everything
 * being split is one. An entity is a point; a chunk being random-ticked is a
 * 16-block span, and has to be interior along its whole width or a block edited
 * at its border reaches into the next cell.
 */
public final class Grid {

    /** No offset can contain the span. Valid packed cell indices never use this value. */
    static final long NO_CELL = Long.MIN_VALUE;

    private final int regionBlocks;
    private final int margin;
    private final int half;

    public Grid(int regionBlocks, int margin) {
        this.regionBlocks = regionBlocks;
        this.margin = margin;
        this.half = regionBlocks / 2;
    }

    public int regionBlocks() {
        return this.regionBlocks;
    }

    public int margin() {
        return this.margin;
    }

    /**
     * Which of the two offsets along one axis -- 0 or half a cell -- the span
     * {@code [min, max]} sits safely inside, or -1 if neither.
     */
    public int axis(double min, double max) {
        long location = locate(min, max);
        return location == NO_CELL ? -1 : (int) (location & 1);
    }

    /** Cell index in the high bits and offset in bit zero, computed together. */
    long locate(double min, double max) {
        double cell = Math.floor(min / this.regionBlocks);
        double start = cell * this.regionBlocks;
        if (min - start >= this.margin && start + this.regionBlocks - max >= this.margin) {
            return (long) (int) cell << 1;
        }
        double shifted = min - this.half;
        cell = Math.floor(shifted / this.regionBlocks);
        start = cell * this.regionBlocks;
        if (shifted - start >= this.margin && start + this.regionBlocks - (max - this.half) >= this.margin) {
            return (long) (int) cell << 1 | 1;
        }
        return NO_CELL;
    }

    /** The cell index along one axis, for a span already known to be interior. */
    public int cell(double min, int axis) {
        return (int) Math.floor((min - axis * this.half) / this.regionBlocks);
    }

    /** The pass a span belongs to, from its two axis results. */
    public static int pass(int axisX, int axisZ) {
        return axisX | axisZ << 1;
    }
}
