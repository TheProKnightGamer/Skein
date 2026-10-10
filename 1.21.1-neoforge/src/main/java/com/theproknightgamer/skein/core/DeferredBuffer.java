package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.Skein;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityInLevelCallback;

/**
 * One region task's worth of parked structural writes.
 *
 * <p>Skein used to hang a single {@link java.util.concurrent.ConcurrentLinkedQueue}
 * off the level and let every worker push into it. That cost a CAS and a node
 * allocation per deferred write -- of which a busy tick has thousands, since
 * every entity that crosses a section boundary produces one -- and it made the
 * order the coordinator replayed them in depend on how the threads happened to
 * interleave.
 *
 * <p>A buffer belongs to exactly one task, so it needs no synchronisation at
 * all, and the coordinator drains the buffers in task order. Same tick, same
 * split, same replay order, every time.
 *
 * <p>The three commonest operations are encoded rather than wrapped in a
 * lambda. An entity section transfer is by far the most frequent deferred write
 * in the game, and {@code self::onMove} allocates a capture object for each one.
 */
public final class DeferredBuffer {
    private static final byte OP_RUN = 0;
    private static final byte OP_MOVE = 1;
    private static final byte OP_REMOVE = 2;
    private static final byte OP_SPAWN = 3;

    private byte[] ops = new byte[64];
    private Object[] targets = new Object[64];
    private Object[] args = new Object[64];
    private int size;

    public boolean isEmpty() {
        return this.size == 0;
    }

    public int size() {
        return this.size;
    }

    /** Parks an arbitrary action. */
    public void run(Runnable action) {
        push(OP_RUN, action, null);
    }

    /** Parks an entity joining the level; see {@link #drain(Level, DeferredBuffer)}. */
    public void spawn(Runnable add) {
        push(OP_SPAWN, add, null);
    }

    /** Parks an entity's move between {@code EntitySection}s. */
    public void move(EntityInLevelCallback callback) {
        push(OP_MOVE, callback, null);
    }

    /** Parks an entity's teardown. */
    public void remove(EntityInLevelCallback callback, Entity.RemovalReason reason) {
        push(OP_REMOVE, callback, reason);
    }

    private void push(byte op, Object target, Object arg) {
        if (this.size == this.ops.length) {
            grow();
        }
        this.ops[this.size] = op;
        this.targets[this.size] = target;
        this.args[this.size] = arg;
        this.size++;
    }

    private void grow() {
        int capacity = this.ops.length * 2;
        this.ops = java.util.Arrays.copyOf(this.ops, capacity);
        this.targets = java.util.Arrays.copyOf(this.targets, capacity);
        this.args = java.util.Arrays.copyOf(this.args, capacity);
    }

    /**
     * Appends everything parked here to {@code other}, in order, and empties
     * this buffer: what a helper thread parked on a task's behalf joins that
     * task's own buffer exactly where the task would have parked it itself.
     */
    public void transferTo(DeferredBuffer other) {
        for (int i = 0; i < this.size; i++) {
            other.push(this.ops[i], this.targets[i], this.args[i]);
        }
        clear();
    }

    /** Drops everything parked here without applying it. */
    public void clear() {
        java.util.Arrays.fill(this.targets, 0, this.size, null);
        java.util.Arrays.fill(this.args, 0, this.size, null);
        this.size = 0;
    }

    /**
     * Applies everything parked here, in submission order, and empties the
     * buffer. Coordinator only.
     *
     * <p>Indexed rather than iterated because applying one action can
     * legitimately park another -- an entity removal that destroys its vehicle,
     * say -- and those have to run in this same drain.
     */
    public int drain(Level level) {
        return drain(level, null);
    }

    /**
     * As {@link #drain(Level)}, except that spawns go on to {@code held}, in
     * order, when it is not null: the cluster tier adds new entities only once
     * all its levels have run, since isolation was worked out before they
     * existed.
     */
    public int drain(Level level, DeferredBuffer held) {
        int applied = 0;
        for (int i = 0; i < this.size; i++) {
            if (held != null && this.ops[i] == OP_SPAWN) {
                held.push(OP_SPAWN, this.targets[i], null);
                continue;
            }
            try {
                apply(this.ops[i], this.targets[i], this.args[i]);
                applied++;
            } catch (Throwable t) {
                Skein.LOGGER.error("Skein: deferred world mutation failed in {}", level.dimension().location(), t);
            }
        }
        // Cleared afterwards so a nested park during the loop is still seen.
        java.util.Arrays.fill(this.targets, 0, this.size, null);
        java.util.Arrays.fill(this.args, 0, this.size, null);
        this.size = 0;
        return applied;
    }

    private static void apply(byte op, Object target, Object arg) {
        switch (op) {
            case OP_MOVE -> {
                // Dropped if the entity was removed after this move was parked --
                // by another cell's tick, whose removal may well be applied
                // first. Replaying it would add a discarded entity back into a
                // section. The move is safe to lose either way: the removal that
                // overtook it takes the entity out of the section it is still
                // sitting in.
                if (!(target instanceof SkeinDetachable detachable) || !detachable.skein$detached()) {
                    ((EntityInLevelCallback) target).onMove();
                }
            }
            case OP_REMOVE -> ((EntityInLevelCallback) target).onRemove((Entity.RemovalReason) arg);
            default -> ((Runnable) target).run();
        }
    }
}
