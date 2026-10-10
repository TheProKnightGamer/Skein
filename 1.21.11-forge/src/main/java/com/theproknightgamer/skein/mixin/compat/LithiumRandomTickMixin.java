package com.theproknightgamer.skein.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.server.level.ServerLevel;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lithium 0.21's {@code world.chunk_ticking.random_block_ticking} picks random
 * tick positions by stepping {@code Level.randValue} itself, not through
 * {@code getBlockRandomPos}, where {@code LevelMixin} gives each thread its
 * own copy during a pass. Without this, parallel random ticks share one plain
 * int: threads lose each other's steps and start picking the same blocks.
 *
 * <p>Optional: with that Lithium option off the method does not exist.
 */
@HookFor({})
@Mixin(value = ServerLevel.class, remap = false)
public abstract class LithiumRandomTickMixin {

    @WrapOperation(method = "getRandomBlockIndexForRandomTick", at = @At(value = "FIELD",
            opcode = Opcodes.GETFIELD, target = "Lnet/minecraft/server/level/ServerLevel;randValue:I"))
    private int skein$readRandValue(ServerLevel self, Operation<Integer> original) {
        return TickContext.deferring() ? TickContext.local().randValue : original.call(self);
    }

    @WrapOperation(method = "getRandomBlockIndexForRandomTick", at = @At(value = "FIELD",
            opcode = Opcodes.PUTFIELD, target = "Lnet/minecraft/server/level/ServerLevel;randValue:I"))
    private void skein$writeRandValue(ServerLevel self, int value, Operation<Void> original) {
        if (TickContext.deferring()) {
            TickContext.local().randValue = value;
        } else {
            original.call(self, value);
        }
    }
}
