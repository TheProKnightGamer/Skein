package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import it.unimi.dsi.fastutil.objects.AbstractObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * Entity tracking's per-entity half, on the pool.
 *
 * <p>{@code ChunkMap.tick} walks every tracked entity once a tick. For each
 * one it checks whether the entity changed section -- if so, it works out who
 * can see it now -- and, if its chunk is in entity-ticking range, calls
 * {@code ServerEntity.sendChanges}, which compares the entity against what was
 * last sent and hands any packets to the players watching it. A few thousand
 * entities make this a millisecond of the server thread, nearly all of it
 * spent waiting on memory: each entity's tracker, the entity, its synched data,
 * one after another, none of them touched since the last tick.
 *
 * <p>For almost every entity the work belongs to that entity alone.
 * {@code sendChanges} reads the entity and writes its own tracker and the
 * entity's dirty flags; its only way out is the tracker's
 * {@code ServerEntity.Synchronizer} methods, to the players already watching.
 * So that part runs on the pool, with each send the entity would have made
 * collected -- which method, and what it was given -- instead of made. Vanilla's
 * loop then runs as always, over just the entities that still need it: those
 * with packets to send, which it sends in its own order to the same watchers,
 * and those that must run where they are --
 *
 * <ul>
 *   <li>an entity that changed section, whose watchers are about to change;</li>
 *   <li>a player, whose own connection {@code sendChanges} writes to;</li>
 *   <li>an item frame, which sends map data straight to connections;</li>
 *   <li>a vehicle whose passengers changed, whose passengers packet goes out
 *       through a filter that reads the tracker's {@code lastPassengers} when
 *       it is applied -- after {@code sendChanges} has already replaced it.</li>
 * </ul>
 *
 * <p>Every other entity has nothing left to do in the loop, so it is left out
 * of it: out of range, it would have done nothing; in range, its
 * {@code sendChanges} has already run and sent nothing. Each connection
 * receives the same packets, in the same order, as it would have.
 */
public final class TrackingPass {
    private TrackingPass() {}

    /** Below this many tracked entities the walk is cheaper than handing it out. Tests lower it. */
    public static volatile int minEntities = 512;

    /** Entities tests keep on the live path, to compare against the pool's; null outside tests. */
    public static volatile java.util.function.Predicate<Entity> keepLive;

    private static final LongAdder WALKS = new LongAdder();
    private static final LongAdder SENT_ON_POOL = new LongAdder();
    private static final LongAdder UNSENT = new LongAdder();

    /** Walks run on the pool, for tests and benchmarks. */
    public static long walks() {
        return WALKS.sum();
    }

    /** Entities whose {@code sendChanges} ran on the pool, for tests and benchmarks. */
    public static long sentOnPool() {
        return SENT_ON_POOL.sum();
    }

    /** Entities whose collected packets the loop never asked for: must stay zero. */
    public static long unsent() {
        return UNSENT.sum();
    }

    /** Implemented by {@code ChunkMap$TrackedEntity}. */
    public interface Tracked {
        Entity skein$entity();

        ServerEntity skein$serverEntity();

        SectionPos skein$lastSectionPos();

        /** Makes a send collected on the pool, through the same method, now. */
        void skein$sendNow(Object collected);
    }

    /** Implemented by {@code ServerEntity}. */
    public interface Paired {
        List<Entity> skein$lastPassengers();
    }

    /**
     * Where the calling thread is collecting a tracked entity's packets, or
     * null if it is not: the broadcast should then go out as usual.
     */
    public static List<Object> collector() {
        return TickContext.local().trackedPackets;
    }

    public static boolean eligible() {
        return SkeinConfig.Live.enabled && SkeinConfig.Live.threadedEntityTracking
                && Skein.pool() != null && Skein.pool().size() > 1;
    }

    private static final byte SKIP = 0;
    private static final byte LIVE = 1;
    private static final byte SEND = 2;

    /**
     * One level's walk: the tracked entities vanilla's loop still has to visit,
     * in its order, each with the packets its {@code sendChanges} produced on
     * the pool, or none to run it live. Handed to the loop in place of the
     * entity map's values; reused from tick to tick.
     */
    public static final class Walk extends AbstractObjectCollection<Object> {
        private Object[] tracked = new Object[0];
        private byte[] states = new byte[0];
        private Object[][] packets = new Object[0][];
        private Object[] visit = new Object[0];
        private Object[][] visitPackets = new Object[0][];
        private int visits;
        /** The entry the loop is on, or -1 outside the loop. */
        private int position = -1;

        /**
         * Works out, on the pool, everything the loop would do to
         * {@code values} that belongs to one entity alone, and returns this,
         * ready to be walked in their place.
         */
        public Walk prepare(ServerLevel level, DistanceManager distance, Iterable<?> values, int count) {
            if (this.tracked.length < count) {
                int size = Math.max(count, this.tracked.length * 2);
                this.tracked = new Object[size];
                this.states = new byte[size];
                this.packets = new Object[size][];
                this.visit = new Object[size];
                this.visitPackets = new Object[size][];
            }
            int n = 0;
            for (Object entry : values) {
                if (n == count) {
                    break;
                }
                this.tracked[n++] = entry;
            }
            Object[] tracked = this.tracked;
            byte[] states = this.states;
            Object[][] packets = this.packets;
            WALKS.increment();
            ChunkScan.run(level, n, i -> states[i] = look((Tracked) tracked[i], distance, packets, i));
            int visits = 0;
            int sent = 0;
            for (int i = 0; i < n; i++) {
                if (states[i] != SKIP) {
                    this.visit[visits] = tracked[i];
                    this.visitPackets[visits++] = packets[i];
                    if (states[i] == SEND) sent++;
                }
                tracked[i] = null;
                packets[i] = null;
            }
            this.visits = visits;
            this.position = -1;
            SENT_ON_POOL.add(sent);
            return this;
        }

        /** One entity's share of the loop, on a pool thread. */
        private static byte look(Tracked tracked, DistanceManager distance, Object[][] packets, int index) {
            Entity entity = tracked.skein$entity();
            SectionPos section = SectionPos.of(entity);
            if (!Objects.equals(tracked.skein$lastSectionPos(), section)) {
                return LIVE;
            }
            // The loop calls sendChanges for a changed section, a pending sync or
            // an entity in range, and asks again after this has run. sendChanges
            // clears needsSync, so only range -- which it cannot change -- may
            // decide an early run; a pending sync out of range stays live.
            if (!distance.inEntityTickingRange(section.chunk().toLong())) {
                return entity.needsSync ? LIVE : SKIP;
            }
            ServerEntity server = tracked.skein$serverEntity();
            java.util.function.Predicate<Entity> keepLive = TrackingPass.keepLive;
            if (keepLive != null && keepLive.test(entity)) {
                return LIVE;
            }
            if (entity instanceof ServerPlayer || entity instanceof ItemFrame
                    || !entity.getPassengers().equals(((Paired) server).skein$lastPassengers())) {
                return LIVE;
            }
            // 1.21.11 rebuilds a mob's effect particles inside sendChanges. On
            // NeoForge that posts an event per effect and keeps the entity on
            // the level's thread; Forge posts nothing there, and the rebuild
            // only writes the entity's own synced data.
            TickContext.Local local = TickContext.local();
            ArrayList<Object> collected = local.trackedBuffer;
            collected.clear();
            local.trackedPackets = collected;
            try {
                server.sendChanges();
            } finally {
                local.trackedPackets = null;
            }
            if (collected.isEmpty()) {
                return SKIP;
            }
            packets[index] = collected.toArray();
            collected.clear();
            return SEND;
        }

        /**
         * Sends the packets the entry the loop is on collected on the pool, and
         * returns true; or returns false if its {@code sendChanges} has not run
         * and should run now.
         */
        public boolean sendCollected(ServerEntity server) {
            int at = this.position;
            if (at < 0 || at >= this.visits) {
                return false;
            }
            Tracked tracked = (Tracked) this.visit[at];
            Object[] collected = this.visitPackets[at];
            if (collected == null || tracked.skein$serverEntity() != server) {
                return false;
            }
            this.visitPackets[at] = null;
            for (Object send : collected) {
                tracked.skein$sendNow(send);
            }
            return true;
        }

        /** Lets go of every entity once the loop is over. */
        public void finish() {
            for (int i = 0; i < this.visits; i++) {
                if (this.visitPackets[i] != null) {
                    UNSENT.increment();
                }
            }
            Arrays.fill(this.visit, 0, this.visits, null);
            Arrays.fill(this.visitPackets, 0, this.visits, null);
            this.visits = 0;
            this.position = -1;
        }

        @Override
        public int size() {
            return this.visits;
        }

        @Override
        public ObjectIterator<Object> iterator() {
            this.position = -1;
            return new ObjectIterator<>() {
                @Override
                public boolean hasNext() {
                    return Walk.this.position + 1 < Walk.this.visits;
                }

                @Override
                public Object next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    return Walk.this.visit[++Walk.this.position];
                }
            };
        }
    }
}
