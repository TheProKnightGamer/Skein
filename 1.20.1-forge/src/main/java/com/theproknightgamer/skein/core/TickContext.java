package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.Skein;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.BlockSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Answers one question, as cheaply as possible: "is the calling thread allowed
 * to touch this level right now?" -- and carries the handful of things that
 * have to be private to a thread while several of them share a level.
 *
 * <p>Every mixin in this mod is a variation on that question, and several of
 * them sit in code that runs tens of thousands of times per tick
 * ({@code ServerChunkCache#getChunkNow} in particular), so the lookup has to be
 * a field read rather than a map probe. Worker threads are a dedicated
 * {@link Worker} subclass carrying their own {@link Local}; the server thread
 * gets a static one that only it ever reads.
 *
 * <p>The {@code thread == serverThread} guard in {@link #owns} is not
 * redundant. Netty, the chunk generation pool and mod threads all call into
 * level code, and without the identity check a stale read of {@code MAIN.level}
 * could hand one of them a false claim of ownership -- which would route it
 * past exactly the safety code it needs.
 */
public final class TickContext {
    private TickContext() {}

    /**
     * Everything a thread owns privately for the duration of a task.
     *
     * <p>{@code level} and {@code deferring} answer the ownership question.
     * The rest exists because vanilla keeps it on the level, where it is
     * shared: a neighbour-update state machine, a random source, and
     * NeoForge's block-snapshot capture bracket. All three are single-threaded
     * by construction in vanilla, and all three are handed out per thread here
     * so they stay that way.
     */
    public static final class Local {
        /** Level this thread is currently ticking, or null when idle. */
        public Level level;
        /** True while inside a parallel entity phase, where structural writes are deferred. */
        public boolean deferring;

        /** True inside a {@link ClusterPass} task; see {@link TickContext#clustered()}. */
        public boolean clustered;

        /**
         * The entity, block entity ticker, block or fluid this thread is
         * ticking inside a parallel pass, for the stall report: a worker that
         * never comes back is still holding the thing it was ticking.
         */
        public volatile Object ticking;

        /** A piston push being collected on this thread; see {@link PistonPushes}. */
        public Object pistonBatch;

        /** The entity whose collision lookup this thread is making; see {@link CollisionQueries}. */
        public net.minecraft.world.entity.Entity collisionSource;

        /**
         * Bumped by every world mutation this thread makes. Only read as a
         * before/after pair around one call, so it never needs to be exact
         * across threads -- see {@link HazardWatch}.
         */
        public long worldWrites;
        /** Where this thread's deferred structural writes land during a phase. */
        public DeferredBuffer buffer;
        /**
         * Where the cluster tier's coordinator puts entities that join the
         * level while it replays a level's parked writes, until the tier ends.
         */
        public DeferredBuffer heldSpawns;
        /** This thread's private draw from {@code Level.random}; created on first use. */
        public RandomSource random;
        /**
         * This thread's own copy of {@code Level.randValue}, the tiny linear
         * generator behind {@code getBlockRandomPos}. It is a plain int stepped
         * in place, so two threads picking random tick positions at once would
         * lose each other's updates and land on the same block.
         */
        public int randValue = RandomSource.create().nextInt();
        /**
         * This thread's copy of {@code RedStoneWireBlock.shouldSignal}, the flag
         * a wire clears around its own power recalculation so that it does not
         * count a neighbouring wire as a source. It lives on the block
         * singleton, which is one object for every level at once, so one
         * thread's bracket would otherwise decide what every other thread's
         * wire is worth.
         */
        public boolean wireShouldSignal = true;
        /** The component of scheduled ticks this thread is working through, if any. */
        public ScheduledTickScope scheduledScope;
        /**
         * The phase whose item ({@link #ticking}) this thread is running in an
         * {@link OrderedGroups} task, or null outside one. A game event fired
         * within that item's reach is heard on the spot, as in vanilla.
         */
        public OrderedGroups.Work<?> ordered;
        /**
         * This thread's copy of the chunk cache, and counters for what it is
         * doing. Held here rather than in a {@code ThreadLocal} because
         * {@code getChunk} is the single hottest path in the mod and the
         * ownership check has already resolved this object by the time the
         * cache is consulted -- one thread lookup instead of two.
         */
        public final ChunkSlots chunkSlots = new ChunkSlots();
        public long chunkHits;
        public long chunkMisses;
        public long chunkLockNanos;
        /** Where this thread is collecting an entity's tracking packets; see {@link TrackingPass}. */
        public java.util.List<Object> trackedPackets;
        public final ArrayList<Object> trackedBuffer = new ArrayList<>();
        /** NeoForge's capture bracket, per thread instead of per level. */
        public boolean capturing;
        public boolean restoring;
        public final ArrayList<BlockSnapshot> captured = new ArrayList<>();
        /** This thread's copy of {@code Block.capturedDrops}, while it is ticking a level. */
        public List<ItemEntity> drops;

        /**
         * Closes every bracket vanilla opens and closes inside one call.
         *
         * <p>Each of them is balanced only when the call returns normally:
         * NeoForge's placement capture has no {@code finally}, nor does the
         * redstone wire's. Vanilla would have crashed at the exception that
         * skips the closing half, so there it never matters. Skein logs the
         * exception and keeps the thread, which is a long-lived worker or the
         * server thread itself -- and a thread left capturing skips
         * {@code markAndNotifyBlock} on every block it sets afterwards, in
         * any level, for as long as it lives.
         */
        void resetBrackets() {
            this.capturing = false;
            this.restoring = false;
            this.captured.clear();
            this.drops = null;
            this.wireShouldSignal = true;
            this.trackedPackets = null;
        }
    }

    /** Marker thread type for the Skein pool, used as a cheap context carrier. */
    public static final class Worker extends Thread {
        final Local local = new Local();
        /** Set by the worker before it parks, so {@link SkeinPool} knows who is worth waking. */
        public volatile boolean parked;

        public Worker(Runnable body, String name) {
            super(body, name);
            setDaemon(true);
        }
    }

    private static volatile Thread serverThread;
    /**
     * Replaced whenever a server starts, so nothing -- an open bracket, a
     * chunk cache pointing into the last world -- outlives the server it
     * belonged to. Only ever read by the server thread, which is also the
     * thread that replaces it.
     */
    private static Local MAIN = new Local();
    /**
     * Any other thread that somehow reaches a per-thread accessor. It never
     * owns a level and never defers, so this only ever hands back empty state
     * -- but handing back a shared one would be a race.
     */
    private static final ThreadLocal<Local> STRAY = ThreadLocal.withInitial(Local::new);

    public static void bindServerThread(Thread thread) {
        MAIN = new Local();
        serverThread = thread;
    }

    /**
     * Drops the stopped server's state -- its chunk caches point into a world
     * that is being closed -- so a singleplayer session does not keep the last
     * world reachable until the next one starts.
     */
    public static void unbindServerThread() {
        MAIN = new Local();
        serverThread = null;
    }

    /** After a task that threw was logged and skipped; see {@link Local#resetBrackets}. */
    public static void resetBrackets() {
        local().resetBrackets();
    }

    /** The calling thread's private state. */
    /**
     * For the stall report: what each thread is ticking, sampled twice a
     * quarter of a second apart. A thread still on the same entity, block
     * entity or block is the stuck one, and its type is demoted and
     * remembered: the thread is not coming back, but the type need not get the
     * chance to do this again after the restart.
     */
    static void reportStuck(Worker[] workers) {
        Local[] locals = new Local[workers.length + 1];
        for (int i = 0; i < workers.length; i++) {
            locals[i] = workers[i].local;
        }
        // The server thread runs cells too, but only another thread can report it.
        locals[workers.length] = Thread.currentThread() != serverThread ? MAIN : null;
        Object[] seen = new Object[locals.length];
        for (int i = 0; i < locals.length; i++) {
            seen[i] = locals[i] == null ? null : locals[i].ticking;
        }
        java.util.concurrent.locks.LockSupport.parkNanos(250_000_000L);
        for (int i = 0; i < locals.length; i++) {
            Local local = locals[i];
            Level level = local == null ? null : local.level;
            if (seen[i] != null && local.ticking == seen[i] && level != null) {
                Skein.LOGGER.error("Skein: {} has been ticking {} for the whole stall",
                        i < workers.length ? workers[i].getName() : "the server thread", Demotions.describe(seen[i]));
                Demotions.hung(seen[i], level);
            }
        }
    }

    public static Local local() {
        Thread thread = Thread.currentThread();
        if (thread instanceof Worker worker) {
            return worker.local;
        }
        return thread == serverThread ? MAIN : STRAY.get();
    }

    /** Snapshot of a thread's context, for save/restore around a task. */
    public record State(Level level, boolean deferring, boolean clustered, DeferredBuffer buffer) {}

    public static State enter(Level level, boolean deferring, DeferredBuffer buffer) {
        return enter(level, deferring, buffer, false);
    }

    /**
     * @param clustered true for a {@link ClusterPass} task, where entities are
     *                  separated by the conflict graph rather than by distance
     *                  and so may be arbitrarily close together
     */
    public static State enter(Level level, boolean deferring, DeferredBuffer buffer, boolean clustered) {
        Local local = local();
        State previous = new State(local.level, local.deferring, local.clustered, local.buffer);
        local.level = level;
        // Tied together deliberately: "deferring" is a promise that structural
        // writes have somewhere to go, so it can only be true with a buffer.
        local.deferring = deferring && buffer != null;
        local.clustered = clustered && local.deferring;
        local.buffer = buffer;
        return previous;
    }

    public static void exit(State previous) {
        Local local = local();
        local.level = previous.level();
        local.deferring = previous.deferring();
        local.clustered = previous.clustered();
        local.buffer = previous.buffer();
        if (previous.level() == null) {
            // Leaving level work altogether: every bracket is closed by now
            // unless something threw through one.
            local.resetBrackets();
        }
    }

    /**
     * True inside a cluster-tier task.
     *
     * <p>The grid keeps two concurrent entities at least two margins apart, so
     * whatever they write is in different chunks. The cluster tier keeps them
     * apart only by what they can reach <em>each other</em> with, so two of them
     * can be standing on adjacent blocks -- and then anything that writes the
     * world on their behalf is unseparated. Callers that write blocks use this
     * to park the write instead.
     */
    public static boolean clustered() {
        Thread thread = Thread.currentThread();
        if (thread instanceof Worker worker) {
            return worker.local.clustered;
        }
        return thread == serverThread && MAIN.clustered;
    }

    /**
     * True when the calling thread is the one currently ticking {@code level}
     * and so may treat it as exclusively owned.
     */
    public static boolean owns(Level level) {
        Thread thread = Thread.currentThread();
        if (thread instanceof Worker worker) {
            return worker.local.level == level;
        }
        return thread == serverThread && MAIN.level == level;
    }

    /**
     * True when the caller is inside a parallel entity phase and must therefore
     * push structural mutations onto {@link DeferredWork} instead of applying
     * them inline.
     */
    public static boolean deferring() {
        Thread thread = Thread.currentThread();
        if (thread instanceof Worker worker) {
            return worker.local.deferring;
        }
        return thread == serverThread && MAIN.deferring;
    }

    /** The level the caller is currently ticking, or null. */
    public static Level current() {
        Thread thread = Thread.currentThread();
        if (thread instanceof Worker worker) {
            return worker.local.level;
        }
        return thread == serverThread ? MAIN.level : null;
    }

    /**
     * The component of scheduled ticks this thread is inside, or null. Read by
     * {@code LevelTicks#willTickThisTick}, which has to be answered from the
     * component rather than from the level-wide set while a pass is open.
     */
    public static ScheduledTickScope scheduledScope() {
        Thread thread = Thread.currentThread();
        if (thread instanceof Worker worker) {
            return worker.local.scheduledScope;
        }
        return thread == serverThread ? MAIN.scheduledScope : null;
    }

    /** The buffer deferred writes from this thread belong in, or null. */
    public static DeferredBuffer buffer() {
        Thread thread = Thread.currentThread();
        if (thread instanceof Worker worker) {
            return worker.local.buffer;
        }
        return thread == serverThread ? MAIN.buffer : null;
    }

    /**
     * This thread's own draw from the level's random source.
     *
     * <p>Only consulted while a parallel entity phase is open, so outside one
     * every caller keeps using the level's own instance and vanilla's stream is
     * untouched.
     */
    public static RandomSource random() {
        Local local = local();
        RandomSource source = local.random;
        if (source == null) {
            source = RandomSource.create();
            local.random = source;
        }
        return source;
    }
}
