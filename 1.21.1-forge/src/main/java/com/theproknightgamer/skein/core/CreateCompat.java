package com.theproknightgamer.skein.core;

import java.util.List;

/**
 * What Create needs to tick its block entities in parallel.
 *
 * <p>Almost everything Create does is short-range and fine in a cell: belts,
 * chain conveyors, arms, fans, pumps, funnels, chutes and crafters all reach
 * less than the margin. What is not is the bookkeeping behind them. A kinetic
 * network's members and stress totals are plain {@code HashMap}s and float
 * counters written from the tick of every member, and a network has no size
 * limit, so two cells add, remove and re-sum the same network at once: a
 * second network object for the same id, phantom stress saved into the block
 * entities' NBT, a rotation walk tearing out gearboxes another walk just
 * powered. Brass tunnels share two static scratch maps across every tunnel in
 * every dimension; redstone links share one frequency map per level; the 6.0
 * logistics network and its summary caches are server-wide.
 *
 * <p>All of those are entered through a handful of methods, each now holding
 * {@link #LOCK}. They are entered when something changes -- a member joins or
 * leaves, a speed or a stress value moves, an item is split -- not on every
 * tick, so the lock costs nothing measurable, and one lock for all of them
 * means no ordering between them to get wrong. It is re-entrant, as a rotation
 * walk calls back into the network.
 *
 * <p>The block entities whose own tick reaches past the margin are kept on the
 * level thread instead; see {@link #LONG_REACH}.
 */
public final class CreateCompat {
    private CreateCompat() {}

    public static final Object LOCK = new Object();

    /**
     * Block entity types that tick on the level thread whenever they are
     * registered, whatever the config says. A display link writes to a target
     * up to 64 blocks away; a redstone link powers receivers up to 256 away;
     * the 6.0 logistics blocks pull from packagers anywhere on their network.
     */
    public static final List<String> LONG_REACH = List.of(
            "create:display_link",
            "create:redstone_link",
            "create:factory_panel",
            "create:packager",
            "create:repackager",
            "create:packager_link",
            "create:stock_ticker",
            "create:redstone_requester");
}
