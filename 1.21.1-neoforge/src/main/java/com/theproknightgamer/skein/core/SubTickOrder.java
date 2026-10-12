package com.theproknightgamer.skein.core;

import net.minecraft.world.level.Level;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;

/**
 * Sub-tick numbers for ticks scheduled inside a parallel pass.
 *
 * <p>A scheduled tick is stamped with the level's next sub-tick number when it
 * is created, and among ticks due on the same game tick at the same priority
 * that number is the whole of the run order. Vanilla hands the numbers out in
 * the order the ticks are scheduled. Workers scheduling side by side would hand
 * them out in whatever order the threads happened to reach the counter, so the
 * next tick's order would depend on thread timing, and could differ from
 * vanilla's even where it did not differ between runs.
 *
 * <p>So a tick created on a worker carries {@link #UNSTAMPED} instead, and is
 * parked with the write that schedules it (as every schedule inside a pass
 * already was). The number is drawn when the coordinator applies that write.
 * Since the coordinator applies parked writes in vanilla's order, the numbers
 * come out in vanilla's order too -- in the scheduled-tick phase they are the
 * very numbers vanilla would have drawn.
 */
public final class SubTickOrder {
    private SubTickOrder() {}

    /** What a tick created inside a pass carries until its schedule is applied. */
    public static final long UNSTAMPED = Long.MIN_VALUE;

    /**
     * The schedule of a tick that may still need its number. Drawn from
     * {@code level}, the level the tick was created for.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Runnable schedule(LevelTicks container, ScheduledTick<?> tick, Level level) {
        return () -> {
            ScheduledTick<?> stamped = tick;
            if (tick.subTickOrder() == UNSTAMPED) {
                stamped = new ScheduledTick<>(tick.type(), tick.pos(), tick.triggerTick(), tick.priority(),
                        level.nextSubTickCount());
            }
            container.schedule(stamped);
        };
    }
}
