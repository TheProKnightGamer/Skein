package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.DimensionTicker;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.util.ITeleporter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The vanilla entities that override {@code changeDimension} to do something around the
 * move -- an item merges with its new neighbours, falling sand notes the End crossing, a villager stops trading. {@link EntityMixin} parks a move made from a level
 * the caller is not ticking at the head of the base method, and hands the
 * entity back; an override would then carry on with its own part, on the
 * caller's thread, against a level another thread is ticking, and do it a
 * second time when the parked move runs. So these park at their own head:
 * the whole override waits for the dimension barrier.
 */
@HookFor(Hooks.Feature.DIMENSIONS)
// 1.20.1: only these two override changeDimension.
@Mixin({ItemEntity.class, AbstractVillager.class})
public abstract class TeleportOverridesMixin {

    @Inject(method = "changeDimension(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraftforge/common/util/ITeleporter;)Lnet/minecraft/world/entity/Entity;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void skein$deferForeignMove(ServerLevel destination, ITeleporter teleporter,
                                   CallbackInfoReturnable<Entity> cir) {
        Entity self = (Entity) (Object) this;
        if (DimensionTicker.crossesTickingLevel(self.level(), destination)
                && DimensionTicker.deferPastBarrier(() -> self.changeDimension(destination, teleporter))) {
            cir.setReturnValue(self);
        }
    }
}
