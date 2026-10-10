package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.SkeinPool;
import com.theproknightgamer.skein.core.TickContext;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.util.Util;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import com.theproknightgamer.skein.core.DimensionTicker;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import java.util.ArrayList;
import java.util.List;

/**
 * The periodic autosave, across the pool.
 *
 * <p>Every autosave interval vanilla walks every loaded chunk and serialises the
 * dirty ones -- {@code ChunkSerializer.write} runs 24 sections through the block
 * state codec, plus biomes, light layers and every block entity's NBT -- with no
 * time budget of any kind. On a large world that is hundreds of milliseconds to
 * seconds on the server thread, which is the biggest single stall vanilla has.
 *
 * <p>It is also the easiest thing in the game to split, and for an unusual
 * reason: the concurrency argument is not a reach bound but simply that
 * <em>nothing is ticking</em>. {@code saveEverything} runs after
 * {@code tickChildren} has returned, so every Skein phase is closed and the
 * server thread is alone in the world. Reads of chunk and entity state cannot
 * race a writer, and chunks are independent of each other.
 *
 * <p>Only the periodic path is touched. The {@code flush} path is startup,
 * shutdown and {@code /save-all flush}, where the loop is a
 * {@code managedBlock} handshake with the main-thread executor that has to stay
 * where it is.
 *
 * <p>What is shared, and is now serialised: {@code chunkSaveCooldowns} (the
 * ten-second per-chunk throttle) and {@code chunkTypeCache}, both plain fastutil
 * maps that {@code save} reads and writes for every chunk. What cannot be made
 * safe from here is {@code ChunkDataEvent.Save}, which fires on whichever worker
 * serialised the chunk, and is why this is off by default.
 *
 * <p>The locks exist only for the parallel save, so all of it goes if any of it
 * is missing.
 */
@HookFor(Hooks.Feature.PARALLEL_SAVING)
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    @Shadow @Final ServerLevel level;

    @Shadow private volatile Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap;

    @Shadow @Final private Long2ByteMap chunkTypeCache;

    @Shadow @Final private Long2LongMap nextChunkSaveTime;

    @Invoker("saveChunkIfNeeded")
    abstract boolean skein$saveChunkIfNeeded(ChunkHolder holder, long now);

    @WrapMethod(method = "saveAllChunks")
    private void skein$saveInParallel(boolean flush, Operation<Void> original) {
        if (!skein$trySaveInParallel(flush)) {
            original.call(flush);
        }
    }

    /** @return true when this took the save; false leaves it to vanilla untouched */
    private boolean skein$trySaveInParallel(boolean flush) {
        SkeinPool pool = Skein.pool();
        if (flush || pool == null || !SkeinConfig.Live.enabled || !SkeinConfig.Live.parallelSaving) {
            return false;
        }
        // Only a save with the levels at rest: from inside a tick the pool is
        // somebody else's, and the level's chunks are being changed.
        if (TickContext.current() != null || DimensionTicker.phaseActive()) {
            return false;
        }
        List<ChunkHolder> holders = new ArrayList<>(this.visibleChunkMap.values());
        if (holders.size() < SkeinConfig.Live.minSaveChunks) {
            return false;
        }

        // One task per slice rather than per chunk: a chunk that is not dirty
        // costs a map lookup, so per-chunk tasks would be mostly dispatch.
        // Vanilla resets the per-chunk save schedule and stamps every save with
        // one clock reading; both happen here, before any worker starts.
        this.nextChunkSaveTime.clear();
        long now = Util.getMillis();
        int slice = Math.max(8, holders.size() / Math.max(1, pool.size() * 4));
        List<Runnable> tasks = new ArrayList<>();
        for (int from = 0; from < holders.size(); from += slice) {
            int to = Math.min(from + slice, holders.size());
            int start = from;
            tasks.add(() -> {
                TickContext.State previous = TickContext.enter(this.level, false, null);
                try {
                    for (int i = start; i < to; i++) {
                        try {
                            skein$saveChunkIfNeeded(holders.get(i), now);
                        } catch (Throwable t) {
                            // Vanilla already swallows per-chunk save failures
                            // into reportChunkSaveFailure; losing the rest of
                            // the slice on top of that would be worse.
                            Skein.LOGGER.error("Skein: saving a chunk failed in {}",
                                    this.level.dimension().identifier(), t);
                        }
                    }
                } finally {
                    TickContext.exit(previous);
                }
            });
        }
        pool.invokeAll(tasks);
        return true;
    }

    // --- the two shared maps save() reads and writes -------------------------

    @WrapOperation(
            method = "saveChunkIfNeeded",
            at = @At(value = "INVOKE",
                     target = "Lit/unimi/dsi/fastutil/longs/Long2LongMap;getOrDefault(JJ)J"))
    private long skein$cooldownGet(Long2LongMap map, long key, long fallback, Operation<Long> original) {
        synchronized (map) {
            return original.call(map, key, fallback);
        }
    }

    @WrapOperation(
            method = "saveChunkIfNeeded",
            at = @At(value = "INVOKE",
                     target = "Lit/unimi/dsi/fastutil/longs/Long2LongMap;put(JJ)J"))
    private long skein$cooldownPut(Long2LongMap map, long key, long value, Operation<Long> original) {
        synchronized (map) {
            return original.call(map, key, value);
        }
    }

    @WrapOperation(
            method = {"markPosition", "markPositionReplaceable"},
            at = @At(value = "INVOKE",
                     target = "Lit/unimi/dsi/fastutil/longs/Long2ByteMap;put(JB)B"))
    private byte skein$typeCachePut(Long2ByteMap map, long key, byte value, Operation<Byte> original) {
        synchronized (map) {
            return original.call(map, key, value);
        }
    }

    @WrapOperation(
            method = "isExistingChunkFull",
            at = @At(value = "INVOKE",
                     target = "Lit/unimi/dsi/fastutil/longs/Long2ByteMap;get(J)B"))
    private byte skein$typeCacheGet(Long2ByteMap map, long key, Operation<Byte> original) {
        synchronized (map) {
            return original.call(map, key);
        }
    }
}
