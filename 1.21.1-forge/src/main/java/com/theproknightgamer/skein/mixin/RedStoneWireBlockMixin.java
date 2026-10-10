package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.world.level.block.RedStoneWireBlock;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The flag a wire clears while it works out its own power, made per thread.
 *
 * <p>{@code calculateTargetStrength} sets {@code shouldSignal} false, asks the
 * level for the best signal reaching this position, and sets it back to true --
 * a bracket that exists so a wire does not count its neighbouring wire as a
 * source while deciding what it is worth. The flag is a plain field on the
 * {@code Blocks.REDSTONE_WIRE} singleton, so it is one boolean for every wire
 * in every dimension, and the bracket spans six calls into arbitrary block
 * code.
 *
 * <p>Two wires recalculating at once therefore hand each other the wrong
 * answer, and the damaging direction is the one that goes outwards: while any
 * thread anywhere holds the flag false, every {@code hasNeighborSignal} in the
 * game reads a live wire as zero, so a torch whose input is powered lights, a
 * door opens, a piston retracts -- and nothing schedules an update to put it
 * back. The other direction leaves a wire counting its neighbour as a full
 * source, computing 15 where it should have computed 14, and quietly keeping a
 * cut line lit.
 *
 * <p>Per thread rather than behind a lock, because
 * {@code getBestNeighborSignal} calls back into whatever blocks happen to be
 * next to the wire. And for as long as a thread is ticking a level rather than
 * only while a pass is open, because the field belongs to the block rather than
 * to a level: two dimensions ticking on their own threads collide on it with
 * every in-level phase turned off -- the same boundary {@link BlockMixin} uses.
 * The bracket is balanced inside one call and the field is true at every method
 * boundary, so a per-thread copy starting out true behaves exactly as vanilla
 * does on a server that only ever has one thread in here. Outside a level tick
 * the real field is used, so a mod that sets it itself still sees its own write.
 */
@Mixin(RedStoneWireBlock.class)
public abstract class RedStoneWireBlockMixin {

    @WrapOperation(
            method = "calculateTargetStrength",
            at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
                     target = "Lnet/minecraft/world/level/block/RedStoneWireBlock;shouldSignal:Z"))
    private void skein$writeShouldSignal(RedStoneWireBlock self, boolean value, Operation<Void> original) {
        // Same test as the read, so a bracket can never be opened on one of the
        // two and closed on the other.
        if (TickContext.current() != null) {
            TickContext.local().wireShouldSignal = value;
        } else {
            original.call(self, value);
        }
    }

    @WrapOperation(
            method = {"getDirectSignal", "getSignal", "isSignalSource"},
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
                     target = "Lnet/minecraft/world/level/block/RedStoneWireBlock;shouldSignal:Z"))
    private boolean skein$readShouldSignal(RedStoneWireBlock self, Operation<Boolean> original) {
        return TickContext.current() != null ? TickContext.local().wireShouldSignal : original.call(self);
    }
}
