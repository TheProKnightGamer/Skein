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
 * Forge's snapshot-restore flag, read per thread in the block drop paths.
 *
 * <p>NeoForge also routes block drops through a static capture list
 * ({@code Block.capturedDrops}); Forge has no such list, so on Forge the only
 * shared state here is {@code restoringBlockSnapshots}, which lives on the level
 * and is set by {@link CommonHooksMixin}'s target. While a pass defers writes,
 * a thread reads its own copy of it.
 */
// Priority above the default so the injections still apply when another mod has
// merged (@Overwrite) one of the target methods -- the PVP-bot mod's herobot mixins
// do that to Block, and Mixin refuses to inject into an equal-priority merge.
@Mixin(value = Block.class, priority = 1500)
public abstract class BlockMixin {

    /**
     * Optional for the same reason: the herobot overwrite of {@code popResource}
     * never consults the flag, so with that mod installed there is nothing here
     * to take over -- and nothing that needs it, since the overwrite has no
     * snapshot-restore suppression to leak between threads.
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
     * Same flag, read through a {@code ServerLevel}-typed parameter, so the field
     * instruction's owner is {@code ServerLevel} and the target has to say so.
     * Paired with {@link CommonHooksMixin}, and like it only per thread while a
     * pass defers.
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
