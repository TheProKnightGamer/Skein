package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.BlockEntityTicker;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The block-entity phase. Vanilla's loop keeps its job of merging pending
 * tickers, dropping removed ones and applying the ticking-range test; the
 * wrapper below only takes the {@code tick()} call away from it, and the
 * collected tickers run in passes once the loop has finished walking the list.
 *
 * <p>Below the default priority for the reason given on {@link ChunkLoopMixin}:
 * a profiler or tick throttler wrapping {@code tick()} stays on the server
 * thread, in the loop, and a ticker it skips is never filed.
 */
@HookFor(Hooks.Feature.BLOCK_ENTITIES)
@Mixin(value = Level.class, priority = 900)
public abstract class BlockEntityLoopMixin {

    @Inject(method = "tickBlockEntities", at = @At("HEAD"))
    private void skein$beginBlockEntities(CallbackInfo ci) {
        BlockEntityTicker.begin((Level) (Object) this);
    }

    @WrapOperation(
            method = "tickBlockEntities",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
    private void skein$collectBlockEntity(TickingBlockEntity ticker, Operation<Void> original) {
        Level self = (Level) (Object) this;
        if (BlockEntityTicker.collecting(self)) {
            BlockEntityTicker.collect(self, ticker);
        } else {
            original.call(ticker);
        }
    }

    @Inject(method = "tickBlockEntities", at = @At("RETURN"))
    private void skein$finishBlockEntities(CallbackInfo ci) {
        // Unconditional: it runs whatever the wrapper collected, and returns
        // immediately when that is nothing.
        BlockEntityTicker.finish((Level) (Object) this);
    }
}
