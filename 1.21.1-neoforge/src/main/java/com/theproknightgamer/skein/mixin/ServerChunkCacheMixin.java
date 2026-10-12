package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.Skein;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.SkeinRefusal;
import com.theproknightgamer.skein.core.ChunkSlots;
import com.theproknightgamer.skein.core.DeferredWork;
import com.theproknightgamer.skein.core.DimensionTicker;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.SkeinLevel;
import com.theproknightgamer.skein.core.TickContext;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.Util;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Teaches the chunk source who is allowed to talk to it, and keeps the parts of
 * it that write off the path everything else takes.
 *
 * <p>Vanilla gates everything here on {@code Thread.currentThread() !=
 * this.mainThread}, an identity captured when the world loaded. Under Skein the
 * thread that legitimately owns a level changes every tick, so the comparison
 * has to be against the current owner. Reporting ownership also keeps every
 * call on the calling thread: the alternative -- vanilla's off-thread route,
 * which posts the work to the level's queue and blocks on the result --
 * deadlocks the moment the thread that would have drained that queue is itself
 * waiting inside a cell task.
 *
 * <p>Running inline means the writes have to be handled directly, and the write
 * is not the chunk lookup -- it is the ticket. {@code getChunk} with
 * {@code load} set adds an {@code UNKNOWN} ticket to the distance manager on
 * every miss, and entities call it several times each per tick simply to ask
 * what fluid they are standing in. Skein splits the method in three:
 *
 * <ol>
 *   <li>a per-thread {@link ChunkSlots} cache, large enough to hold a whole
 *       cell's working set rather than vanilla's four entries;</li>
 *   <li>a lock-free hit against the published chunk map, for the overwhelmingly
 *       common case of a chunk that is already loaded past the requested status
 *       -- there is nothing left to load, so the only write left is the ticket,
 *       and that is parked for the barrier;</li>
 *   <li>the real vanilla call under a per-level lock, for chunks that genuinely
 *       have to be brought in.</li>
 * </ol>
 *
 * <p>Parking the ticket is not a shortcut. {@code UNKNOWN} tickets live one
 * tick and are purged at the top of the chunk-source tick, which has already
 * happened by the time an entity pass opens -- so a ticket added at the barrier
 * and a ticket added inline are the same ticket, with the same lifetime, and
 * the chunk it protects is demonstrably loaded either way.
 *
 * <p>The exclusive level owner also reuses its private cache for dense work
 * that cannot be split into cells. Its misses retain the original chunk-fetch
 * path; both kinds of hits share vanilla's cache-invalidation generation.
 */
// Priority above the default so the thread-identity redirect can still reach
// getChunk when another optimisation mod has overwritten it -- Lithium's
// world.chunk_access does exactly that, and Mixin refuses to inject into a
// merged method from an equal-priority config. Skein cannot make that
// combination fast (see SkeinCompat), but it must not stop the game loading.
@Mixin(value = ServerChunkCache.class, priority = 1500)
public abstract class ServerChunkCacheMixin {

    @Shadow @Final protected Thread mainThread;

    @Shadow @Final public ServerLevel level;

    @Shadow @Final public ChunkMap chunkMap;

    @Shadow @Final private DistanceManager distanceManager;

    @Shadow @Final private long[] lastChunkPos;

    /**
     * Four slots that can never match, handed to {@code getChunkNow}'s lookup
     * loop while several threads share the level, so it skips straight to the
     * chunk map instead of reading three arrays nobody is synchronising.
     */
    @Unique
    private static final long[] SKEIN_POISONED_CACHE = {
            ChunkPos.INVALID_CHUNK_POS, ChunkPos.INVALID_CHUNK_POS,
            ChunkPos.INVALID_CHUNK_POS, ChunkPos.INVALID_CHUNK_POS
    };

    @WrapOperation(
            method = {"getChunk", "getChunkNow", "getChunkFuture"},
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
                     target = "Lnet/minecraft/server/level/ServerChunkCache;mainThread:Ljava/lang/Thread;"))
    private Thread skein$currentOwner(ServerChunkCache self, Operation<Thread> original) {
        if (TickContext.owns(this.level)) {
            return Thread.currentThread();
        }
        Thread main = original.call(self);
        // The identity vanilla recorded is the server thread's. While the
        // dimensions are ticking, the server thread is ticking one of them and
        // is no more this level's owner than any other worker; answering yes
        // would send it straight into a chunk map another thread is inside.
        if (main == Thread.currentThread() && TickContext.current() != null && DimensionTicker.phaseActive()) {
            return null;
        }
        return main;
    }

    @HookFor(Hooks.Feature.IN_LEVEL)
    @WrapOperation(
            method = "getChunkNow",
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
                     target = "Lnet/minecraft/server/level/ServerChunkCache;lastChunkPos:[J"))
    private long[] skein$bypassSharedCache(ServerChunkCache self, Operation<long[]> original) {
        return TickContext.deferring() ? SKEIN_POISONED_CACHE : original.call(self);
    }

    /** The other half of {@link #skein$bypassSharedCache}. */
    @HookFor(Hooks.Feature.IN_LEVEL)
    @Inject(method = "storeInCache", at = @At("HEAD"), cancellable = true)
    private void skein$skipSharedCacheWrite(long position, ChunkAccess chunk, ChunkStatus status, CallbackInfo ci) {
        if (TickContext.deferring()) {
            ci.cancel();
        }
    }

    @Inject(method = "clearCache", at = @At("HEAD"))
    private void skein$invalidateThreadCaches(CallbackInfo ci) {
        ((SkeinLevel) this.level).skein$bumpChunkGeneration();
    }

    /**
     * {@code hasChunk} from the same per-thread cache {@code getChunk} uses.
     *
     * <p>Entities ask it several times a tick each -- before fluid pushing,
     * before checking the blocks they are inside -- and it goes to the chunk
     * holder map every time. Under SuperChunk that path ends in a synchronized
     * map inside its chunk system, so a parallel entity pass had every worker
     * queueing on one monitor to learn that the chunk it is standing in exists.
     *
     * <p>An entry in this thread's slots at FULL was stored only while the
     * holder's ticket level admitted FULL, which is exactly what
     * {@code hasChunk} tests, and any change to ticket levels passes through
     * {@code clearCache} and voids the entry. So a hit answers true; anything
     * else asks vanilla.
     */
    @WrapMethod(method = "hasChunk")
    private boolean skein$cachedHasChunk(int x, int z, Operation<Boolean> original) {
        TickContext.Local local = TickContext.local();
        if (SkeinConfig.Live.enabled && (local.deferring || local.level == this.level)
                && local.chunkSlots.get(this, ((SkeinLevel) this.level).skein$chunkGeneration(),
                        ChunkPos.asLong(x, z), ChunkStatus.FULL) != null) {
            return true;
        }
        return original.call(x, z);
    }

    @WrapMethod(method = "getChunk")
    private ChunkAccess skein$guardChunkFetch(int x, int z, ChunkStatus status, boolean load,
                                              Operation<ChunkAccess> original) {
        // One thread lookup for the whole method: the ownership answer, the
        // cache and its counters all hang off the same object.
        TickContext.Local local = TickContext.local();
        if (local.level != this.level && local.level != null) {
            skein$refuseAcrossLevels(local.level);
        }
        // `original` is only ever called here, never handed on: passed to a helper
        // that is not inlined, it escapes, and the Operation the wrapper builds
        // for every call -- cache hits included -- can no longer be scalar-replaced.
        // It was one in twelve of the objects an entity-heavy tick allocated.
        if (!local.deferring) {
            if (local.level == this.level && SkeinConfig.Live.enabled) {
                // Dense, interacting work stays on its level owner but still
                // benefits from a private cache.
                long position = ChunkPos.asLong(x, z);
                ChunkAccess cached = local.chunkSlots.get(this, ((SkeinLevel) this.level).skein$chunkGeneration(),
                        position, status);
                if (cached != null) {
                    return cached;
                }
                // Retain the vanilla ticket, lifecycle and loading path on a miss.
                ChunkAccess result = original.call(x, z, status, load);
                skein$rememberOwnerFetch(position, status, result, local);
                return result;
            }
            return original.call(x, z, status, load);
        }
        long position = ChunkPos.asLong(x, z);
        long generation = ((SkeinLevel) this.level).skein$chunkGeneration();
        ChunkAccess cached = local.chunkSlots.get(this, generation, position, status);
        if (cached != null) {
            local.chunkHits++;
            return cached;
        }
        return skein$fetchUncached(x, z, status, load, local, position, generation);
    }

    /** Caches what the level owner fetched, unless the chunk is still mid-load. */
    @Unique
    private void skein$rememberOwnerFetch(long position, ChunkStatus status, ChunkAccess result,
            TickContext.Local local) {
        ChunkHolder holder = this.chunkMap.getVisibleChunkIfPresent(position);
        if (result != null && holder != null && holder.currentlyLoading == null) {
            local.chunkSlots.put(this, ((SkeinLevel) this.level).skein$chunkGeneration(), position, status, result);
        }
    }

    /** Keep ticket handling and the refusal out of the inlined cache-hit path. */
    @Unique
    private ChunkAccess skein$fetchUncached(int x, int z, ChunkStatus status, boolean load,
            TickContext.Local local, long position, long generation) {
        ChunkSlots slots = local.chunkSlots;
        local.chunkMisses++;

        ChunkHolder holder = this.chunkMap.getVisibleChunkIfPresent(position);
        if (holder != null) {
            LevelChunk loading = holder.currentlyLoading;
            if (loading != null) {
                // Mid-load, and deliberately not cached: the field is cleared
                // the moment the load finishes.
                return loading;
            }
            if (holder.getTicketLevel() <= ChunkLevel.byStatus(status)) {
                ChunkAccess published = holder.getChunkIfPresent(status);
                if (published != null) {
                    if (load) {
                        skein$parkTicket(holder.getPos(), status);
                    }
                    slots.put(this, generation, position, status, published);
                    local.chunkHits++;
                    local.chunkMisses--;
                    return published;
                }
            }
        }

        // Nothing published at that status, so vanilla would load it here and
        // now. A parallel tick must not. The load is the chunk system's
        // main-thread work -- the UNKNOWN ticket, the distance manager's
        // trackers, holder futures -- and, through managedBlock, everything
        // else waiting in the level's main-thread queue: other chunks' FULL
        // promotions, which add their entities to the section storage, the
        // lookup, the tick list and the tracker, register tick containers and
        // fire ChunkEvent.Load. All of that ran here, on this worker, while
        // every other worker read the same structures without a lock. A
        // per-level lock only kept loaders apart from each other. The 1.21.11
        // line found the same thing as a crash on 2026-09-09.
        //
        // An optional read gets vanilla's own answer for an unloaded chunk; a
        // required one aborts this tick, which the cell logs and survives.
        // Either way the asker belonged on the level thread, and
        // entities.loadedMarginChunks is what puts most of them there.
        skein$warnRefused(x, z, status, load);
        if (!load) {
            return null;
        }
        throw new SkeinRefusal("Skein: refusing to load chunk [" + x + ", " + z + "] at " + status
                + " from a parallel tick; the ticking entity or block should have been pinned"
                + " (see entities.loadedMarginChunks)");
    }

    @Unique
    private static final AtomicLong SKEIN_LAST_REFUSAL = new AtomicLong();

    /** One line every five seconds at most: the position and the thread, so the asker can be found. */
    @Unique
    private static void skein$warnRefused(int x, int z, ChunkStatus status, boolean load) {
        long now = System.nanoTime();
        long last = SKEIN_LAST_REFUSAL.get();
        if (now - last > 5_000_000_000L && SKEIN_LAST_REFUSAL.compareAndSet(last, now)) {
            Skein.LOGGER.warn("Skein: refused to load chunk [{}, {}] at {} for a parallel tick on {} ({} read);"
                    + " the asker should tick serially -- raise entities.loadedMarginChunks if it is a mob",
                    x, z, status, Thread.currentThread().getName(), load ? "required" : "optional");
        }
    }

    /**
     * A level's tick asking for another level's chunks while both are ticking.
     *
     * <p>Vanilla gameplay does this from a command -- a command block in the
     * Nether running {@code execute in minecraft:overworld run setblock} -- and
     * mods do it by reaching for {@code server.overworld()}. Neither can be
     * served. On a worker vanilla's off-thread path queues the lookup for the
     * other level's owner and blocks until it runs, but that owner does not
     * serve its queue in the middle of a tick, so the barrier never closes. On
     * the server thread it would go straight into a chunk map another thread
     * is inside.
     *
     * <p>So the asking level is moved out of the parallel set -- it ticks after
     * the others from now on, where reaching across is safe -- and this one
     * request fails. That costs one command once, where the alternative is a
     * hang.
     */
    @Unique
    private void skein$refuseAcrossLevels(net.minecraft.world.level.Level from) {
        if (!DimensionTicker.phaseActive() || !SkeinConfig.Live.enabled) {
            return;
        }
        DimensionTicker.reachedAcross(from, this.level);
        throw new SkeinRefusal("Skein: " + from.dimension().location() + " asked for a chunk in "
                + this.level.dimension().location() + " while both were ticking in parallel; "
                + from.dimension().location() + " ticks after the other dimensions from now on");
    }

    @Unique
    private void skein$parkTicket(ChunkPos pos, ChunkStatus status) {
        DistanceManager manager = this.distanceManager;
        int ticketLevel = ChunkLevel.byStatus(status);
        Runnable write = () -> manager.addTicket(TicketType.UNKNOWN, pos, ticketLevel, pos);
        if (!skein$parkWrite(write)) {
            write.run();
        }
    }

    /**
     * Where a distance-manager write goes while several threads are sharing the
     * world.
     *
     * <p>A cell's own buffer is the right place only while the write stays
     * inside the level the cell belongs to, because that is the level its
     * coordinator has to itself when it drains. A write aimed at a
     * <em>different</em> level -- which is what a tick reaching into another
     * dimension does -- would land there on a ticket map that the thread owning
     * that level is purging and propagating at the same moment, and one such
     * write is enough to leave a {@code Long2ObjectOpenHashMap} that either
     * drops the ticket or never finishes a lookup. Those wait for the dimension
     * barrier, which is the only point where no level is running at all.
     *
     * <p>Nothing a tick can see depends on these writes until chunks next load
     * and unload, so they are parked as order-only: the scheduled-tick phase
     * keeps a group running past them.
     *
     * @return true if the write was parked, false if the caller still has to do
     *         it
     */
    @Unique
    private boolean skein$parkWrite(Runnable write) {
        if (TickContext.current() != this.level && DimensionTicker.deferPastBarrier(write)) {
            return true;
        }
        return DeferredWork.parkOrdered(write);
    }

    /**
     * Region tickets, which unlike the {@code UNKNOWN} ticket above are added by
     * ordinary world code rather than by the chunk lookup.
     *
     * <p>The one that matters is the portal ticket: an entity finishing a
     * dimension transition asks the level it has just arrived in to hold that
     * chunk, and it does so from the tick of the level it came from. Nothing
     * else in Skein sees that call -- it is not a chunk fetch and not a
     * structural entity write -- so it is caught here. A ticket added a fraction
     * of a tick later is the same ticket: nothing between the two points can
     * unload the chunk, because nothing between the two points is ticking.
     */
    @Inject(
            method = "addRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;Z)V",
            at = @At("HEAD"), cancellable = true)
    private <T> void skein$parkAddRegionTicket(TicketType<T> type, ChunkPos pos, int distance, T value,
                                               boolean forceTicks, CallbackInfo ci) {
        DistanceManager manager = this.distanceManager;
        if (skein$parkWrite(() -> manager.addRegionTicket(type, pos, distance, value, forceTicks))) {
            ci.cancel();
        }
    }

    @Inject(
            method = "removeRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;Z)V",
            at = @At("HEAD"), cancellable = true)
    private <T> void skein$parkRemoveRegionTicket(TicketType<T> type, ChunkPos pos, int distance, T value,
                                                  boolean forceTicks, CallbackInfo ci) {
        // Parked on the same terms as the add, so a pair issued during one tick
        // is still applied in the order it was issued.
        DistanceManager manager = this.distanceManager;
        if (skein$parkWrite(() -> manager.removeRegionTicket(type, pos, distance, value, forceTicks))) {
            ci.cancel();
        }
    }

    /**
     * The asynchronous door into the same loads, under the same rules: a chunk
     * that is already published is handed back complete, anything else is
     * refused while a pass is open, and a level may not ask for another
     * level's chunks while both are ticking.
     */
    @WrapMethod(method = "getChunkFuture")
    private CompletableFuture<ChunkResult<ChunkAccess>> skein$guardChunkFuture(
            int x, int z, ChunkStatus status, boolean load,
            Operation<CompletableFuture<ChunkResult<ChunkAccess>>> original) {
        TickContext.Local local = TickContext.local();
        if (local.level != this.level && local.level != null) {
            skein$refuseAcrossLevels(local.level);
        }
        if (!local.deferring) {
            return original.call(x, z, status, load);
        }
        ChunkHolder holder = this.chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(x, z));
        if (holder != null && holder.getTicketLevel() <= ChunkLevel.byStatus(status)) {
            ChunkAccess published = holder.getChunkIfPresent(status);
            if (published != null) {
                if (load) {
                    skein$parkTicket(holder.getPos(), status);
                }
                return CompletableFuture.completedFuture(ChunkResult.of(published));
            }
        }
        skein$warnRefused(x, z, status, load);
        return CompletableFuture.completedFuture(ChunkResult.error("Skein: a parallel tick may not load chunks"));
    }

    /**
     * Player chunk tracking, which is the last chunk-system write an ordinary
     * entity tick can reach without asking for a chunk or adding a ticket.
     *
     * <p>{@code move} looks like a position update and is nothing of the sort.
     * It walks every {@code TrackedEntity} in the level to re-pair it with this
     * player, and when the player has crossed a section it calls
     * {@code DistanceManager.removePlayer} and {@code addPlayer} -- and each of
     * those drives two {@code ChunkTracker}s: priority queues of plain linked
     * sets with no synchronisation, drained on the level's thread between
     * ticks. One worker-side update interleaved with that drain leaves the
     * queue broken, and the next drain dies. That was the 1.21.11 line's
     * 2026-09-12 and 2026-09-17 crash.
     *
     * <p>Nothing in vanilla calls this from an entity tick -- it is driven by
     * the movement packet handler and the spectator camera -- but a mod's
     * headless player has no packets, so it is called by hand from the tick
     * instead, and a projectile that teleports its owner reaches it too.
     * Parking costs nothing: vanilla applies a player's tracking change once
     * per tick anyway, and the replay re-reads the player's section when it
     * runs, so two parked moves collapse into the one the second wanted.
     */
    @Inject(method = "move(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), cancellable = true)
    private void skein$parkPlayerMove(ServerPlayer player, CallbackInfo ci) {
        if (!TickContext.deferring() && TickContext.current() == this.level) {
            return;
        }
        ServerChunkCache self = (ServerChunkCache) (Object) this;
        if (skein$parkWrite(() -> self.move(player))) {
            ci.cancel();
        }
    }

    /**
     * The other door into the same trackers: {@code ChunkMap.addEntity} and
     * {@code removeEntity} call {@code updatePlayerStatus} for a player. Every
     * other entity already reaches these only from a replay; a player's
     * arrival does not, because {@code ServerLevel.addPlayer} bypasses
     * {@code addEntity}. Parked in submission order alongside everything else,
     * so a removal issued before an add is still applied before it --
     * {@code ChunkMap.addEntity} throws on an entity that is still tracked.
     */
    @Inject(method = "addEntity(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void skein$parkTrackingStart(Entity entity, CallbackInfo ci) {
        if (!TickContext.deferring() && TickContext.current() == this.level) {
            return;
        }
        ServerChunkCache self = (ServerChunkCache) (Object) this;
        if (skein$parkWrite(() -> self.addEntity(entity))) {
            ci.cancel();
        }
    }

    @Inject(method = "removeEntity(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void skein$parkTrackingEnd(Entity entity, CallbackInfo ci) {
        if (!TickContext.deferring() && TickContext.current() == this.level) {
            return;
        }
        ServerChunkCache self = (ServerChunkCache) (Object) this;
        if (skein$parkWrite(() -> self.removeEntity(entity))) {
            ci.cancel();
        }
    }
}
