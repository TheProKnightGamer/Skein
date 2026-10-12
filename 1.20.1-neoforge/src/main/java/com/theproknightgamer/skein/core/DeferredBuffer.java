package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.Skein;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.ticks.ScheduledTick;

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
 *
 * <p>The scheduled-tick phase asks for more than a fixed order: it needs
 * vanilla's. Every write is stamped with a key -- there, the position in
 * vanilla's run order of the tick that made it -- so that the coordinator can
 * merge the groups' buffers back into the one sequence vanilla would have
 * produced ({@link #applyKey}). And it needs to know when a write was parked
 * that the rest of the group could have seen in vanilla ({@link #takeObservable}),
 * and which ticks are waiting here ({@link #hasParkedTick}), because some blocks
 * ask.
 */
public final class DeferredBuffer {
    private static final byte OP_RUN = 0;
    private static final byte OP_MOVE = 1;
    private static final byte OP_REMOVE = 2;
    private static final byte OP_SPAWN = 3;

    private byte[] ops = new byte[64];
    private Object[] targets = new Object[64];
    private Object[] args = new Object[64];
    private long[] keys = new long[64];
    private int size;
    /** Where {@link #applyKey} has got to. */
    private int cursor;
    /** Stamped on everything parked from now on; see {@link #key(long)}. */
    private long key;
    /** Set when a write is parked that later work in this task could have seen; see {@link #takeObservable}. */
    private boolean observable;

    /**
     * The ticks parked here, per tick container, for {@code hasScheduledTick}.
     * Kept from tick to tick and cleared, since redstone parks a few every tick.
     */
    private Object[] tickOwners = new Object[2];
    @SuppressWarnings("unchecked")
    private ObjectOpenCustomHashSet<ScheduledTick<?>>[] parkedTicks = new ObjectOpenCustomHashSet[2];
    private int tickOwnerCount;

    public boolean isEmpty() {
        return this.size == 0;
    }

    public int size() {
        return this.size;
    }

    /** Parks an arbitrary action. */
    public void run(Runnable action) {
        push(OP_RUN, action, null);
        this.observable = true;
    }


    /**
     * Parks an action whose effect nothing later in the same task could see
     * in vanilla either -- a block event joining the queue, a client update --
     * so that only its place in the order matters.
     */
    public void runOrdered(Runnable action) {
        push(OP_RUN, action, null);
    }

    /** Parks an entity joining the level; see {@link #drain(Level, DeferredBuffer)}. */
    public void spawn(Runnable add) {
        push(OP_SPAWN, add, null);
        this.observable = true;
    }

    /** Parks an entity's move between {@code EntitySection}s. */
    public void move(EntityInLevelCallback callback) {
        push(OP_MOVE, callback, null);
        this.observable = true;
    }

    /** Parks an entity's teardown. */
    public void remove(EntityInLevelCallback callback, Entity.RemovalReason reason) {
        push(OP_REMOVE, callback, reason);
        this.observable = true;
    }

    /**
     * Stamps everything parked from now on with {@code key}. Keys must not go
     * down within one task, which is what lets {@link #applyKey} walk the
     * buffer once.
     */
    public void key(long key) {
        this.key = key;
    }

    /**
     * Whether anything was parked since the last call that later work in this
     * task could have seen had it not been parked -- an entity it could find,
     * a listener it could reach -- and resets the answer.
     */
    public boolean takeObservable() {
        boolean was = this.observable;
        this.observable = false;
        return was;
    }

    /** Records a tick on its way to {@code owner}, parked here along with the write that schedules it. */
    public void parkTick(Object owner, ScheduledTick<?> tick) {
        for (int i = 0; i < this.tickOwnerCount; i++) {
            if (this.tickOwners[i] == owner) {
                this.parkedTicks[i].add(tick);
                return;
            }
        }
        if (this.tickOwnerCount == this.tickOwners.length) {
            this.tickOwners = java.util.Arrays.copyOf(this.tickOwners, this.tickOwnerCount * 2);
            this.parkedTicks = java.util.Arrays.copyOf(this.parkedTicks, this.tickOwnerCount * 2);
        }
        ObjectOpenCustomHashSet<ScheduledTick<?>> set = this.parkedTicks[this.tickOwnerCount];
        if (set == null) {
            set = new ObjectOpenCustomHashSet<>(ScheduledTick.UNIQUE_TICK_HASH);
            this.parkedTicks[this.tickOwnerCount] = set;
        }
        this.tickOwners[this.tickOwnerCount++] = owner;
        set.add(tick);
    }

    /**
     * True when a tick of {@code type} at {@code pos} is parked here for
     * {@code owner}: vanilla would have put it in the container already, and
     * {@code hasScheduledTick} would see it.
     */
    public boolean hasParkedTick(Object owner, BlockPos pos, Object type) {
        for (int i = 0; i < this.tickOwnerCount; i++) {
            if (this.tickOwners[i] == owner) {
                return this.parkedTicks[i].contains(ScheduledTick.probe(type, pos));
            }
        }
        return false;
    }

    private void push(byte op, Object target, Object arg) {
        if (this.size == this.ops.length) {
            grow();
        }
        this.ops[this.size] = op;
        this.targets[this.size] = target;
        this.args[this.size] = arg;
        this.keys[this.size] = this.key;
        this.size++;
    }

    private void grow() {
        int capacity = this.ops.length * 2;
        this.ops = java.util.Arrays.copyOf(this.ops, capacity);
        this.targets = java.util.Arrays.copyOf(this.targets, capacity);
        this.args = java.util.Arrays.copyOf(this.args, capacity);
        this.keys = java.util.Arrays.copyOf(this.keys, capacity);
    }

    /**
     * Appends everything parked here to {@code other}, in order, and empties
     * this buffer: what a helper thread parked on a task's behalf joins that
     * task's own buffer exactly where the task would have parked it itself,
     * under the key that task is working on.
     */
    public void transferTo(DeferredBuffer other) {
        for (int i = 0; i < this.size; i++) {
            other.push(this.ops[i], this.targets[i], this.args[i]);
        }
        other.observable |= this.observable;
        for (int i = 0; i < this.tickOwnerCount; i++) {
            for (ScheduledTick<?> tick : this.parkedTicks[i]) {
                other.parkTick(this.tickOwners[i], tick);
            }
        }
        clear();
    }

    /** Drops everything parked here without applying it. */
    public void clear() {
        java.util.Arrays.fill(this.targets, 0, this.size, null);
        java.util.Arrays.fill(this.args, 0, this.size, null);
        this.size = 0;
        reset();
    }

    private void reset() {
        this.cursor = 0;
        this.key = 0;
        this.observable = false;
        for (int i = 0; i < this.tickOwnerCount; i++) {
            this.tickOwners[i] = null;
            this.parkedTicks[i].clear();
        }
        this.tickOwnerCount = 0;
    }

    /**
     * Applies the writes parked under {@code key}, which must be the next key
     * in this buffer if it has any, and stops at the first that is not.
     * Coordinator only; the rest goes with {@link #drain(Level)}.
     */
    public int applyKey(Level level, long key) {
        int applied = 0;
        while (this.cursor < this.size && this.keys[this.cursor] == key) {
            int i = this.cursor++;
            try {
                apply(this.ops[i], this.targets[i], this.args[i]);
                applied++;
            } catch (Throwable t) {
                Skein.LOGGER.error("Skein: deferred world mutation failed in {}", level.dimension().location(), t);
            }
            this.targets[i] = null;
            this.args[i] = null;
        }
        return applied;
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
        for (int i = this.cursor; i < this.size; i++) {
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
        reset();
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
