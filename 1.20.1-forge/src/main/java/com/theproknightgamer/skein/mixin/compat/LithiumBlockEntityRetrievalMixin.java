package com.theproknightgamer.skein.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lithium's {@code util.block_entity_retrieval} answers "no block entity"
 * unless it is asked on {@code Level.thread}. A dimension ticking on one of
 * Skein's threads is asked on another, so hoppers there never hear that the
 * inventory next to them changed, and comparators miss updates -- silently,
 * with Lithium's default config. The thread that owns the level counts as
 * the level's thread here too, as it does for vanilla's
 * {@code getBlockEntity} (see {@code LevelMixin}).
 *
 * <p>Optional: with that Lithium option off the method does not exist.
 *
 * <p>1.20.1: Lithium has no Forge build here; its ports Canary and Radium
 * merge the same method, without the {@code lithium$} prefix, and read the
 * field under its SRG name -- so the method selector is not remapped and the
 * field target is.
 */
@HookFor({})
@Mixin(value = Level.class, remap = false)
public abstract class LithiumBlockEntityRetrievalMixin {

    @WrapOperation(method = "getLoadedExistingBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;",
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
                    target = "Lnet/minecraft/world/level/Level;thread:Ljava/lang/Thread;", remap = true))
    private Thread skein$levelOwner(Level self, Operation<Thread> original) {
        return TickContext.owns(self) ? Thread.currentThread() : original.call(self);
    }
}
