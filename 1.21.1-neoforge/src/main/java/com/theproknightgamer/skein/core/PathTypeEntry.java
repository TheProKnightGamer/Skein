package com.theproknightgamer.skein.core;

import net.minecraft.world.level.pathfinder.PathType;

/**
 * One slot of the shared pathfinding cache: the block a path type was computed
 * for, and the type.
 *
 * <p>The pairing is the point. Kept as two arrays, as vanilla keeps them, a
 * reader on another thread can find a position that matches its query sitting
 * next to a type that was computed for a different block. Kept as one object,
 * published once, there is nothing to interleave.
 */
public record PathTypeEntry(long position, PathType type) {}
