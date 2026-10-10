package com.theproknightgamer.skein.core;

/** Conservative bucket bounds for the floating-point distance comparisons. */
final class SpatialBounds {
    private SpatialBounds() {}

    static int floor(double value) {
        return (int) Math.floor(value);
    }

    static int lower(double coordinate, double reach, double bucket) {
        // A subtraction can round down to exactly reach even when the true
        // distance is slightly larger. Expand the radius and round both the
        // subtraction and division outward so that such a point is not lost.
        return floor(Math.nextDown(Math.nextDown(coordinate - Math.nextUp(reach)) / bucket));
    }

    static int upper(double coordinate, double reach, double bucket) {
        return floor(Math.nextUp(Math.nextUp(coordinate + Math.nextUp(reach)) / bucket));
    }
}
