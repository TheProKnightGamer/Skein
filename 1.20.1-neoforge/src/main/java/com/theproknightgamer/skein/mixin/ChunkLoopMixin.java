package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.ChunkTicker;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
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

/**
 * Random ticks, lifted out of vanilla's chunk loop.
 *
 * <p>Vanilla walks its ticking chunks once, and for each one increments
 * inhabited time, offers it to the mob spawner, and random-ticks it. Only the
 * last of those is safe to run across threads -- the spawner's caps are a
 * running total over the whole level -- so the wrapper below files the chunk
 * instead of ticking it, the loop finishes doing everything else serially, and
 * the phase runs just before the loop's results are broadcast.
 *
 * <p>Which means all spawning now happens before all random ticking, rather
 * than the two being interleaved chunk by chunk. The only thing that can see
 * the difference is a spawn attempt that would have noticed a block grown by a
 * random tick earlier in the same loop, one tick sooner.
 *
 * <p>Below the default priority on purpose. MixinExtras nests wrappers of one
 * call by priority, lowest innermost, so another mod's wrapper around
 * {@code tickChunk} stays outside this one: it runs on the server thread, in
 * the loop's order, and decides whether the chunk is ticked at all before
 * Skein files it. Above the default it would be the inner wrapper that Skein
 * carries onto a worker.
 */
@HookFor(Hooks.Feature.RANDOM_TICKS)
@Mixin(value = ServerChunkCache.class, priority = 900)
public abstract class ChunkLoopMixin {

    @Shadow @Final public ServerLevel level;

    /**
     * The random-tick speed vanilla read once before its loop. Captured rather
     * than re-read so that whatever the loop was going to use is what the
     * parallel phase uses, including if another mod changed it.
     */
    @Unique
    private int skein$randomTickSpeed;

    @Inject(method = "tickChunks", at = @At("HEAD"))
    private void skein$beginRandomTicks(CallbackInfo ci) {
        ChunkTicker.begin(this.level);
    }

    @WrapOperation(
            method = "tickChunks",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/server/level/ServerLevel;tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V"))
    private void skein$collectRandomTicks(ServerLevel level, LevelChunk chunk, int randomTickSpeed,
                                          Operation<Void> original) {
        if (ChunkTicker.collecting(level)) {
            this.skein$randomTickSpeed = randomTickSpeed;
            ChunkTicker.collect(level, chunk);
        } else {
            original.call(level, chunk, randomTickSpeed);
        }
    }

    @Inject(
            method = "tickChunks",
            at = @At(value = "INVOKE", target = "Ljava/util/List;forEach(Ljava/util/function/Consumer;)V"))
    private void skein$finishRandomTicks(CallbackInfo ci) {
        // Before the broadcast, so a block a random tick changed reaches clients
        // in the tick that changed it.
        ChunkTicker.finish(this.level, this.skein$randomTickSpeed);
    }
}
