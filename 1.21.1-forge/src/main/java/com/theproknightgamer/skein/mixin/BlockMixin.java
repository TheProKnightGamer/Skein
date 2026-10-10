package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The block-snapshot rollback flag as {@code Block} reads it, made per thread.
 *
 * <p>NeoForge also collects a broken block's drops through a {@code static}
 * list, and the NeoForge build of Skein keeps that list per thread. Forge has
 * no such list -- {@code popResource} spawns each drop directly -- so there is
 * nothing shared to take over on this loader, and the hooks are absent.
 *
 * <p>{@code restoringBlockSnapshots} gets the per-thread treatment -- see
 * {@link ForgeHooksMixin}, which is what sets it.
 */
@Mixin(Block.class)
public abstract class BlockMixin {

    /**
     * Paired with {@link ForgeHooksMixin}, and like it only per thread while a
     * pass defers. Optional for the reason above: an overwrite of
     * {@code popResource} that never reads the flag has nothing to leak.
     */
    @HookFor({})
    @WrapOperation(
            method = "popResource(Lnet/minecraft/world/level/Level;Ljava/util/function/Supplier;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
                     target = "Lnet/minecraft/world/level/Level;restoringBlockSnapshots:Z"))
    private static boolean skein$restoringHere(Level level, Operation<Boolean> original) {
        return TickContext.deferring() ? TickContext.local().restoring : original.call(level);
    }

    /**
     * The same read in {@code popExperience}, which reaches the field through
     * its {@code ServerLevel} parameter -- so the bytecode names
     * {@code ServerLevel} as the owner, and the target above never matched it.
     * A block broken while this thread was rolling a placement back dropped
     * experience it should not have.
     */
    @HookFor(Hooks.Feature.IN_LEVEL)
    @WrapOperation(
            method = "popExperience(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;I)V",
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
                     target = "Lnet/minecraft/server/level/ServerLevel;restoringBlockSnapshots:Z"))
    private static boolean skein$restoringHereServer(ServerLevel level, Operation<Boolean> original) {
        return TickContext.deferring() ? TickContext.local().restoring : original.call(level);
    }
}
