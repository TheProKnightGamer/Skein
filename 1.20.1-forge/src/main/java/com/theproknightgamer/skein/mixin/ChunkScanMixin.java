package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.ChunkScan;
import com.theproknightgamer.skein.core.DimensionTicker;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/** The chunk tick's two walks over every holder, on the pool; see {@link ChunkScan}. */
@HookFor(Hooks.Feature.CHUNK_SCAN)
@Mixin(ServerChunkCache.class)
public abstract class ChunkScanMixin {

    @Shadow @Final ServerLevel level;

    /** This tick's holders in the order the walk visits them, and each one's ticking chunk. */
    @Unique private ChunkHolder[] skein$scanHolders = new ChunkHolder[0];
    @Unique private LevelChunk[] skein$scanChunks = new LevelChunk[0];
    /** How many of the arrays' entries this tick filled, to be cleared when it ends. */
    @Unique private int skein$scanFilled;
    /** Holders whose answers the walk may take from the arrays, or -1 to ask live. */
    @Unique private int skein$scanSize = -1;
    @Unique private int skein$scanNext;

    /**
     * Hands the walk a snapshot of the holders, so that the first holder's
     * question can answer all of them at once. Nothing the walk does changes
     * which holders there are.
     */
    @WrapOperation(method = "tickChunks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;getChunks()Ljava/lang/Iterable;"))
    private Iterable<ChunkHolder> skein$snapshotHolders(ChunkMap chunkMap, Operation<Iterable<ChunkHolder>> original) {
        Iterable<ChunkHolder> holders = original.call(chunkMap);
        this.skein$scanSize = -1;
        if (!ChunkScan.eligible() || DimensionTicker.isQuarantined(this.level)) {
            return holders;
        }
        ChunkHolder[] snapshot = this.skein$scanHolders;
        int size = 0;
        for (ChunkHolder holder : holders) {
            if (size == snapshot.length) {
                snapshot = Arrays.copyOf(snapshot, Math.max(1024, size * 2));
            }
            snapshot[size++] = holder;
        }
        this.skein$scanHolders = snapshot;
        if (this.skein$scanChunks.length < snapshot.length) {
            this.skein$scanChunks = new LevelChunk[snapshot.length];
        }
        this.skein$scanFilled = size;
        this.skein$scanNext = 0;
        this.skein$scanSize = size >= ChunkScan.minHolders ? size : -1;
        return Arrays.asList(snapshot).subList(0, size);
    }

    /**
     * The first holder's question is asked of every holder, on the pool, with
     * the operation the walk itself would have run -- vanilla's, or whatever
     * another mod put in its place. Each answer is handed back only to the
     * holder it belongs to, in step with the snapshot; a walk that falls out of
     * step asks live from there on.
     */
    @WrapOperation(method = "tickChunks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkHolder;getTickingChunk()Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private LevelChunk skein$scannedTickingChunk(ChunkHolder holder, Operation<LevelChunk> original) {
        int size = this.skein$scanSize;
        if (size < 0) {
            return original.call(holder);
        }
        int index = this.skein$scanNext++;
        ChunkHolder[] holders = this.skein$scanHolders;
        LevelChunk[] chunks = this.skein$scanChunks;
        if (index == 0) {
            ChunkScan.run(this.level, size, i -> chunks[i] = original.call(holders[i]));
        }
        if (index < size && holders[index] == holder) {
            return chunks[index];
        }
        this.skein$scanSize = -1;
        return original.call(holder);
    }

    /**
     * Broadcasts only for the holders with something to broadcast; every other
     * holder's {@code broadcastChanges} would have returned at its first test.
     * After the random-tick phase, which Skein runs just before this.
     */
    @WrapOperation(method = "tickChunks", at = @At(value = "INVOKE",
            target = "Ljava/util/List;forEach(Ljava/util/function/Consumer;)V"))
    private <E> void skein$broadcastChangedOnly(List<E> entries, Consumer<? super E> broadcast,
                                                 Operation<Void> original) {
        if (!ChunkScan.eligible() || entries.size() < ChunkScan.minHolders
                || DimensionTicker.isQuarantined(this.level)) {
            original.call(entries, broadcast);
            return;
        }
        original.call(ChunkScan.changed(this.level, entries), broadcast);
    }

    @Inject(method = "tickChunks", at = @At("RETURN"))
    private void skein$releaseScan(CallbackInfo ci) {
        // The arrays are kept for the next tick, not the chunks in them.
        Arrays.fill(this.skein$scanHolders, 0, this.skein$scanFilled, null);
        Arrays.fill(this.skein$scanChunks, 0, this.skein$scanFilled, null);
        this.skein$scanFilled = 0;
        this.skein$scanSize = -1;
    }
}
