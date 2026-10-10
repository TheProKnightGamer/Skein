package com.theproknightgamer.skein.core;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityInLevelCallback;

/**
 * The mechanism that makes parallel entity ticking tractable.
 *
 * <p>Ticking entities in parallel is mostly fine -- movement, AI, damage. What
 * is not fine is the bookkeeping around them: adding an entity to the level,
 * moving it between {@code EntitySection}s, tearing it down on removal. All
 * three mutate structures shared by the whole level ({@code EntityLookup}'s id
 * and uuid maps, {@code EntitySectionStorage}'s section map and its sorted id
 * set), and all three can fire from any entity's tick.
 *
 * <p>Rather than lock those structures -- which means holding a lock across
 * code that calls back into arbitrary mod logic -- Skein defers them. During a
 * parallel pass the entity registry is <em>read-only</em>, every structural
 * write lands in the running task's {@link DeferredBuffer}, and the coordinator
 * applies the buffers in task order the instant the pass ends. The visible cost
 * is that a mob spawned mid-pass is not returned by an area query until the
 * pass finishes, a sub-tick delay.
 */
public final class DeferredWork {
    private DeferredWork() {}

    /**
     * Queues {@code action} if the caller is inside a parallel pass, otherwise
     * runs it immediately.
     *
     * @return true if the action was deferred, false if it already ran
     */
    public static boolean defer(Level level, Runnable action) {
        DeferredBuffer buffer = target();
        if (buffer == null) {
            action.run();
            return false;
        }
        buffer.run(action);
        return true;
    }

    /**
     * Parks {@code action} if the caller is inside a parallel pass, and does
     * nothing at all otherwise.
     *
     * <p>Separate from {@link #defer} because the callers that need this have
     * somewhere else to put the write when no pass is open, so running it here
     * would run it twice.
     *
     * @return true if the action was parked
     */
    public static boolean park(Runnable action) {
        DeferredBuffer buffer = target();
        if (buffer == null) {
            return false;
        }
        buffer.run(action);
        return true;
    }

    /**
     * Parks a structural write aimed at {@code target}: past the dimension
     * barrier when the caller is ticking some other level while dimensions
     * run in parallel -- a cell buffer is drained with the other levels still
     * running -- into the running pass otherwise, and nowhere when neither is
     * open.
     *
     * @return true if the action was parked
     */
    public static boolean parkFor(Level target, Runnable action) {
        Level current = TickContext.current();
        if (current != null && current != target && DimensionTicker.deferPastBarrier(action)) {
            return true;
        }
        return park(action);
    }

    /**
     * {@link #parkFor} for an entity joining {@code target}. Kept apart from
     * other parked writes so that the cluster tier can hold new entities back
     * until its last level has run -- including ones its coordinator adds
     * while replaying a level's writes; see {@link TickContext.Local#heldSpawns}.
     *
     * @return true if the spawn was parked
     */
    public static boolean parkSpawn(Level target, Runnable add) {
        Level current = TickContext.current();
        if (current != null && current != target && DimensionTicker.deferPastBarrier(add)) {
            return true;
        }
        DeferredBuffer buffer = target();
        if (buffer == null) {
            buffer = TickContext.local().heldSpawns;
            if (buffer == null) {
                return false;
            }
        }
        buffer.spawn(add);
        return true;
    }

    /** Allocation-free deferral of an entity's section transfer. */
    public static boolean deferMove(EntityInLevelCallback callback) {
        DeferredBuffer buffer = target();
        if (buffer == null) {
            return false;
        }
        buffer.move(callback);
        return true;
    }

    /** Allocation-free deferral of an entity's teardown. */
    public static boolean deferRemove(EntityInLevelCallback callback, Entity.RemovalReason reason) {
        DeferredBuffer buffer = target();
        if (buffer == null) {
            return false;
        }
        buffer.remove(callback, reason);
        return true;
    }

    /**
     * The buffer this thread's deferred writes belong in, or null when the
     * caller is not inside a parallel pass and should just act.
     */
    private static DeferredBuffer target() {
        return TickContext.deferring() ? TickContext.buffer() : null;
    }
}
