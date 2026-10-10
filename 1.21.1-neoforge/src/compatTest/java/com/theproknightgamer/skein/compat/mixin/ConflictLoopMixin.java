package com.theproknightgamer.skein.compat.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.compat.ConflictVerification;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A profiler-style mod wrapping each block entity's tick in the loop. Its
 * wrapper must stay outside Skein's: on the level's own thread, never inside a
 * parallel pass.
 */
@Mixin(value = Level.class, priority = 1000)
abstract class ConflictLoopMixin {
    @WrapOperation(method = "tickBlockEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
    private void skeinTest$timeTicker(TickingBlockEntity ticker, Operation<Void> original) {
        ConflictVerification.wrapped(TickContext.deferring());
        original.call(ticker);
    }
}
