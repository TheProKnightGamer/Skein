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
// Priority above the default so the injections still apply when another mod has
// merged (@Overwrite) one of the target methods -- the PVP-bot mod's herobot mixins
// do that to Block, and Mixin refuses to inject into an equal-priority merge.
@Mixin(value = Block.class, priority = 1500)
public abstract class BlockMixin {

    @WrapOperation(
            method = "stopCapturingDrops",
            at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
                     target = "Lnet/minecraft/world/level/block/Block;capturedDrops:Ljava/util/List;"))
    private static List<ItemEntity> skein$readCapturedDrops(Operation<List<ItemEntity>> original) {
        return TickContext.current() != null ? TickContext.local().drops : original.call();
    }

    @WrapOperation(
            method = {"beginCapturingDrops", "stopCapturingDrops"},
            at = @At(value = "FIELD", opcode = Opcodes.PUTSTATIC,
                     target = "Lnet/minecraft/world/level/block/Block;capturedDrops:Ljava/util/List;"))
    private static void skein$writeCapturedDrops(List<ItemEntity> drops, Operation<Void> original) {
        // Same test as the read, so a bracket can never be opened on one of the
        // two and closed on the other.
        if (TickContext.current() != null) {
            TickContext.local().drops = drops;
        } else {
            original.call(drops);
        }
    }

    /**
     * The capture list's reader in {@code popResource}. Optional: a mod that
     * rewrites {@code popResource} without reading the list -- the PvP bot
     * mod's herobot {@code BlockMixin} does -- spawns every drop itself, which
     * leaves nothing shared in that method to keep per thread.
     */
    @HookFor({})
    @WrapOperation(
            method = "popResource(Lnet/minecraft/world/level/Level;Ljava/util/function/Supplier;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
                     target = "Lnet/minecraft/world/level/block/Block;capturedDrops:Ljava/util/List;"))
    private static List<ItemEntity> skein$readCapturedDropsForPop(Operation<List<ItemEntity>> original) {
        return TickContext.current() != null ? TickContext.local().drops : original.call();
    }

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
