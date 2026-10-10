package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * The eager-save queue, written from every thread in a pass.
 *
 * <p>Since 1.21.2 a chunk that changes for the first time after a save tells
 * its {@code ChunkMap} through {@code LevelChunk.markUnsaved}, which adds the
 * chunk to {@code chunksToEagerlySave}: a plain {@code LongLinkedOpenHashSet}.
 * Every block a random tick sets and every block entity that calls
 * {@code setChanged} -- a hopper, each time it moves an item -- goes through
 * it. In a parallel pass that is many workers adding at once, and a concurrent
 * add corrupts the set's links. Measured on Forge 1.21.11 (the same vanilla code as NeoForge): a hopper world threw
 * {@code ArrayIndexOutOfBoundsException} from {@code LongLinkedOpenHashSet.add}
 * within a minute, and the next {@code saveChunksEagerly} walk threw from
 * {@code fixPointers}.
 *
 * <p>The adds are serialised here. The only reader,
 * {@code saveChunksEagerly}, runs in the level's own chunk tick with no pass
 * open, so it needs nothing. The lock is a leaf: nothing under it touches the
 * world or takes another lock. Adds happen once per chunk per save cycle, so it
 * is almost never contended.
 */
@HookFor(Hooks.Feature.IN_LEVEL)
@Mixin(ChunkMap.class)
public abstract class ChunkMapUnsavedMixin {

    @Shadow @Final private LongSet chunksToEagerlySave;

    @WrapMethod(method = "setChunkUnsaved")
    private void skein$serialiseUnsaved(ChunkPos pos, Operation<Void> original) {
        synchronized (this.chunksToEagerlySave) {
            original.call(pos);
        }
    }
}
