package com.theproknightgamer.skein.core;

import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.ScheduledTick;

import java.util.List;
import java.util.Set;

/**
 * One component's answer to {@code willTickThisTick}.
 *
 * <p>Three blocks ask that question -- repeater, comparator and redstone torch
 * -- and all three ask it about their <em>own</em> position, from inside a
 * neighbour update. Vanilla answers from a level-wide set of ticks that have not
 * run yet, maintained as the queue drains.
 *
 * <p>A component keeps its own. That is not an approximation: the asking block
 * is a block away from whatever triggered it, so if it has a tick pending this
 * tick, that tick is inside the same component by construction. Anything in
 * another component is further away than the link distance and cannot be what
 * is being asked about.
 *
 * <p>The set is built on first use, as vanilla's is, because most ticks never
 * ask.
 */
public final class ScheduledTickScope {

    private final Object owner;
    private final List<? extends ScheduledTick<?>> ticks;
    private int index;
    private Set<ScheduledTick<?>> remaining;

    public ScheduledTickScope(Object owner, List<? extends ScheduledTick<?>> ticks) {
        this.owner = owner;
        this.ticks = ticks;
    }

    /** True when this scope belongs to the tick container being asked. */
    public boolean owns(Object container) {
        return this.owner == container;
    }

    /**
     * Marks the tick at the current index as about to run and advances. Vanilla
     * removes a tick from its set when it polls it, so while a tick is running
     * the set holds the ones after it.
     */
    public void advance() {
        if (this.remaining != null) {
            this.remaining.remove(this.ticks.get(this.index));
        }
        this.index++;
    }

    public boolean willTick(BlockPos pos, Object type) {
        if (this.remaining == null || this.remaining.isEmpty() && this.index < this.ticks.size()) {
            this.remaining = new ObjectOpenCustomHashSet<>(ScheduledTick.UNIQUE_TICK_HASH);
            for (int i = this.index; i < this.ticks.size(); i++) {
                this.remaining.add(this.ticks.get(i));
            }
        }
        return this.remaining.contains(ScheduledTick.probe(type, pos));
    }
}
