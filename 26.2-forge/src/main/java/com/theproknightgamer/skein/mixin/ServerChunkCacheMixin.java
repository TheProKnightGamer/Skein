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
import net.minecraft.util.Util;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import java.util.function.Consumer;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
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
import java.util.concurrent.atomic.AtomicBoolean;

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

    @Shadow @Final private TicketStorage ticketStorage;

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
                        ChunkPos.pack(x, z), ChunkStatus.FULL) != null) {
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
                long position = ChunkPos.pack(x, z);
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
        SkeinLevel skeinLevel = (SkeinLevel) this.level;
        long position = ChunkPos.pack(x, z);
        long generation = skeinLevel.skein$chunkGeneration();
        ChunkSlots slots = local.chunkSlots;

        ChunkAccess cached = slots.get(this, generation, position, status);
        if (cached != null) {
            local.chunkHits++;
            return cached;
        }
        local.chunkMisses++;

        ChunkHolder holder = ((com.theproknightgamer.skein.mixin.ChunkMapInvoker) this.chunkMap).skein$getVisibleChunkIfPresent(position);
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
        // now. A parallel tick must not: the load runs the chunk system's
        // main-thread work -- the UNKNOWN ticket, the distance manager's
        // trackers, holder futures, NeoForge's accessibility tracker -- on this
        // thread while the level's own thread and the other workers carry on.
        // NeoForge asserts on it ("onChunkAccessible called on wrong thread"),
        // and the half-applied tracker updates that abort leaves behind surfaced
        // on 2026-09-09 as a LeveledPriorityQueue with broken links, fatal the
        // next time a player's ChunkMap.move ran. An optional read gets vanilla's
        // own answer for an unloaded chunk; a required one aborts this tick,
        // which the cell logs and survives. Either way the asker belonged on the
        // level thread -- EntityTicker's loaded-margin pin is what puts it there.
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
        throw new SkeinRefusal("Skein: " + from.dimension().identifier() + " asked for a chunk in "
                + this.level.dimension().identifier() + " while both were ticking in parallel; "
                + from.dimension().identifier() + " ticks after the other dimensions from now on");
    }

    /** Caches what the level owner fetched, unless the chunk is still mid-load. */
    @Unique
    private void skein$rememberOwnerFetch(long position, ChunkStatus status, ChunkAccess result,
            TickContext.Local local) {
        ChunkHolder holder = ((com.theproknightgamer.skein.mixin.ChunkMapInvoker) this.chunkMap).skein$getVisibleChunkIfPresent(position);
        if (result != null && holder != null && holder.currentlyLoading == null) {
            local.chunkSlots.put(this, ((SkeinLevel) this.level).skein$chunkGeneration(), position, status, result);
        }
    }

    @Unique
    private void skein$parkTicket(ChunkPos pos, ChunkStatus status) {
        TicketStorage storage = this.ticketStorage;
        int ticketLevel = ChunkLevel.byStatus(status);
        Runnable write = () -> storage.addTicket(new Ticket(TicketType.UNKNOWN, ticketLevel), pos);
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
    @Inject(method = "addTicketWithRadius", at = @At("HEAD"), cancellable = true)
    private void skein$parkAddRadiusTicket(TicketType type, ChunkPos pos, int radius, CallbackInfo ci) {
        TicketStorage storage = this.ticketStorage;
        if (skein$parkWrite(() -> storage.addTicketWithRadius(type, pos, radius))) {
            ci.cancel();
        }
    }

    @Inject(method = "removeTicketWithRadius", at = @At("HEAD"), cancellable = true)
    private void skein$parkRemoveRadiusTicket(TicketType type, ChunkPos pos, int radius, CallbackInfo ci) {
        // Parked on the same terms as the add, so a pair issued during one tick
        // is still applied in the order it was issued.
        TicketStorage storage = this.ticketStorage;
        if (skein$parkWrite(() -> storage.removeTicketWithRadius(type, pos, radius))) {
            ci.cancel();
        }
    }

    /**
     * Player chunk tracking, which is the last chunk-system write an ordinary
     * entity tick can reach without asking for a chunk or adding a ticket.
     *
     * <p>{@code move} looks like a position update and is nothing of the sort.
     * It walks every {@code TrackedEntity} in the level to re-pair it with this
     * player, and when the player has crossed a section it calls
     * {@code DistanceManager.removePlayer} and {@code addPlayer} -- and each of
     * those drives two {@code ChunkTracker}s, {@code naturalSpawnChunkCounter}
     * and {@code playerTicketManager}. A {@code ChunkTracker} is a
     * {@code DynamicGraphMinFixedPoint} over a {@code LeveledPriorityQueue}:
     * a plain {@code LongLinkedOpenHashSet} per level plus a {@code firstQueuedLevel}
     * cursor, with no synchronisation anywhere, drained on the server thread by
     * {@code ServerChunkCache.pollTask -> runDistanceManagerUpdates} between
     * ticks. One worker-side {@code update} interleaved with that drain is
     * enough to leave the set's links or the cursor inconsistent, and the next
     * drain dies -- {@code NoSuchElementException} out of
     * {@code removeFirstLong}, or an index of -1 out of {@code fixPointers}.
     * That is the 2026-09-12 and 2026-09-17 crash, and the queue was already
     * broken before the frame that reported it.
     *
     * <p>Nothing in vanilla calls this from an entity tick, which is why it was
     * never on the list -- vanilla drives it from the movement packet handler and
     * from the spectator camera, both on the server thread. But a headless player
     * has no packets, so every mod that runs one calls it by hand from the tick
     * instead; and any projectile that teleports its owner reaches it from the
     * projectile's tick, which is squarely inside a parallel cell.
     *
     * <p>Parking is the whole fix and costs nothing. Vanilla itself only applies
     * a player's tracking change once per tick, from {@code ChunkMap.tick}; the
     * barrier is earlier than that, and the replay re-reads {@code lastSectionPos}
     * when it runs rather than capturing it here, so two parked moves for one
     * player collapse into the transition the second one wanted.
     */
    @Inject(method = "move(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), cancellable = true)
    private void skein$parkPlayerMove(ServerPlayer player, CallbackInfo ci) {
        if (!TickContext.deferring() && TickContext.current() == this.level) {
            return;
        }
        ServerChunkCache self = (ServerChunkCache) (Object) this;
        if (skein$parkWrite(() -> self.move(player))) {
            skein$noteFirstParkedMove();
            ci.cancel();
        }
    }

    /**
     * The other door into the same two trackers: {@code ChunkMap.addEntity} and
     * {@code removeEntity} call {@code updatePlayerStatus} when the entity is a
     * player, which is {@code DistanceManager.addPlayer}/{@code removePlayer}
     * again.
     *
     * <p>For every other entity these are already reached only from a replay --
     * {@code ServerLevelMixin#skein$deferSpawn} parks the spawn and
     * {@code EntitySectionCallbackMixin} parks the teardown, and a replay runs
     * with no pass open -- so this changes nothing for them. A <em>player</em>
     * is the exception, and deliberately so in vanilla: {@code ServerLevel#addPlayer}
     * goes straight to {@code entityManager.addNewEntityWithoutEvent} and never
     * touches {@code ServerLevel#addEntity}, so the one deferral that would have
     * caught it does not see it. That is the arrival half of a dimension change
     * and the respawn path, and a worker can reach both.
     *
     * <p>Parked in submission order alongside everything else, so a removal
     * issued before an add is still applied before it -- which matters here,
     * because {@code ChunkMap.addEntity} throws outright on an entity that is
     * still tracked.
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

    @Unique
    private static final AtomicBoolean SKEIN_REPORTED_PARKED_MOVE = new AtomicBoolean();

    /**
     * One line, once per session, naming the thread that got here. Without it
     * the only evidence this path is live is the crash it used to cause, and
     * the only evidence the fix is live is the absence of one.
     */
    @Unique
    private static void skein$noteFirstParkedMove() {
        if (SKEIN_REPORTED_PARKED_MOVE.compareAndSet(false, true)) {
            Skein.LOGGER.info("Skein: a parallel tick moved a player's chunk tracking on {};"
                    + " the distance-manager write is parked for the barrier. This is logged once.",
                    Thread.currentThread().getName());
        }
    }


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
        // Same rule as getChunk above: a chunk that is already published is
        // handed back complete; anything else may not be loaded by a parallel
        // tick, and gets vanilla's own answer for a chunk that is not there.
        ChunkHolder holder = ((com.theproknightgamer.skein.mixin.ChunkMapInvoker) this.chunkMap).skein$getVisibleChunkIfPresent(ChunkPos.pack(x, z));
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
}
