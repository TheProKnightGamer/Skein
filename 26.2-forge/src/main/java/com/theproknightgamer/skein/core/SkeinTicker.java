package com.theproknightgamer.skein.core;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Gets at the {@code BlockEntity} behind a {@code TickingBlockEntity}.
 *
 * <p>{@code TickingBlockEntity} deliberately exposes almost nothing -- a
 * position, a removal flag, and a type <em>name</em>, built fresh from the
 * registry on every call. Deciding whether a block entity is one of the few
 * whose tick reaches further than the margin is a per-ticker question asked
 * thousands of times a tick, and answering it by building a string each time is
 * not worth it. Both of vanilla's implementations know their block entity;
 * this asks them for it.
 */
public interface SkeinTicker {

    /** The block entity this ticker drives, or null if it is not one of vanilla's wrappers. */
    BlockEntity skein$blockEntity();
}
