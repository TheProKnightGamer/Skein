package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.ChunkTicker;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Random ticks, lifted out of vanilla's chunk loop.
 *
 * <p>Vanilla walks its ticking chunks, offering each to the mob spawner, and
 * then random-ticks the block-ticking ones. Only the random ticks are safe to
 * run across threads -- the spawner's caps are a running total over the whole
 * level -- so the walk below files each chunk instead of ticking it, and the
 * phase runs before {@code tickChunks} goes on to broadcast, so a block a
 * random tick changed reaches clients in the tick that changed it.
 *
 * <p>1.21.11 ticks the block-ticking chunks through a lambda handed to
 * {@code ChunkMap.forEachBlockTickingChunk}, so the {@code tickChunk} call is
 * no longer in this method's bytecode; the walk itself is wrapped, and the
 * walk still happens through whatever else wraps it.
 *
 * <p>Below the default priority on purpose. MixinExtras nests wrappers of one
 * call by priority, lowest innermost, so another mod's wrapper around the walk
 * stays outside this one: it runs on the server thread and decides whether the
 * walk happens at all before Skein files anything.
 */
@HookFor(Hooks.Feature.RANDOM_TICKS)
@Mixin(value = ServerChunkCache.class, priority = 900)
public abstract class ChunkLoopMixin {

    @Shadow @Final public ServerLevel level;

    @Inject(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V", at = @At("HEAD"))
    private void skein$beginRandomTicks(ProfilerFiller profiler, long timeInhabited, CallbackInfo ci) {
        ChunkTicker.begin(this.level);
    }

    @WrapOperation(
            method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/server/level/ChunkMap;forEachBlockTickingChunk(Ljava/util/function/Consumer;)V"))
    private void skein$collectRandomTicks(ChunkMap map, Consumer<LevelChunk> vanillaTick, Operation<Void> original) {
        int randomTickSpeed = this.level.getGameRules().get(GameRules.RANDOM_TICK_SPEED);
        ServerLevel level = this.level;
        if (ChunkTicker.collecting(level)) {
            original.call(map, (Consumer<LevelChunk>) chunk -> ChunkTicker.collect(level, chunk));
        } else {
            original.call(map, vanillaTick);
        }
        ChunkTicker.finish(level, randomTickSpeed);
    }
}
