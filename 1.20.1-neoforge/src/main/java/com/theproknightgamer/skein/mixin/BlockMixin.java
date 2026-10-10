package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * NeoForge's block-drop capture, made per thread.
 *
 * <p>{@code Block.dropResources} opens a bracket -- {@code beginCapturingDrops},
 * roll the loot table, {@code stopCapturingDrops} -- so that everything a broken
 * block produced can be handed to {@code BlockDropsEvent} as one list instead of
 * appearing in the world one item at a time. The list it collects into is a
 * plain {@code static} field.
 *
 * <p>A static field is a bracket shared by every thread and every dimension at
 * once, and dropping blocks is something entities do constantly: creepers,
 * withers, endermen, anything that explodes. Two cells breaking a block in the
 * same tick would have one bracket overwrite the other's list, hand one break's
 * items to the other break's event, and leave the loser spawning its drops
 * straight into the world with no event at all.
 *
 * <p>Which thread is collecting is exactly the kind of thing a thread should be
 * holding, so for as long as a thread is ticking a level it does. A pass is the
 * wrong boundary for this one: the other capture brackets Skein takes over live
 * on the level, where one thread per level already keeps them apart, and this
 * one does not, so two dimensions on their own workers collide on it with every
 * in-level phase turned off. Outside a level tick altogether the vanilla static
 * is used untouched.
 *
 * <p>{@code restoringBlockSnapshots} gets the same treatment for the same
 * reason -- see {@link CommonHooksMixin}, which is what sets it.
 */
@Mixin(Block.class)
public abstract class BlockMixin {

    // 1.21.1's NeoForge adds a static Block.capturedDrops bracket around
    // dropResources, which Skein makes per-thread. Forge 1.20.1 has no such
    // static, so there is nothing to redirect here.

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
