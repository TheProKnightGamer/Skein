package com.theproknightgamer.skein.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.DimensionTicker;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.SuperChunkBridge;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Lithium checks that its chunk-status callbacks run on {@code Level.thread};
 * a dimension ticking on one of Skein's workers, or a SuperChunk cell, is the
 * owner too. Losing it costs dimension threading only.
 */
@HookFor(Hooks.Feature.DIMENSIONS)
@Mixin(targets = "net.caffeinemc.mods.lithium.common.world.chunk.ChunkStatusTracker", remap = false)
public abstract class LithiumChunkStatusTrackerMixin {
    @WrapOperation(method = {"onChunkAccessible", "onChunkInaccessible"},
            at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/lithium/mixin/util/accessors/LevelAccessor;getThread()Ljava/lang/Thread;"))
    private static Thread skein$chunkCallbackOwner(@Coerce Object accessor, Operation<Thread> original,
                                                   @Local(argsOnly = true) ServerLevel level) {
        boolean dimensionOwner = SkeinConfig.Live.enabled && DimensionTicker.phaseActive()
                && TickContext.owns(level) && !TickContext.deferring();
        return dimensionOwner || SuperChunkBridge.ownsChunkTasks(level) ? Thread.currentThread() : original.call(accessor);
    }
}
