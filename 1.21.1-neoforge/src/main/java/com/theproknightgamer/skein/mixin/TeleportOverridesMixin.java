package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.DimensionTicker;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.level.portal.DimensionTransition;
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
@Mixin({ItemEntity.class, FallingBlockEntity.class, PrimedTnt.class, AbstractVillager.class})
public abstract class TeleportOverridesMixin {

    @Inject(method = "changeDimension(Lnet/minecraft/world/level/portal/DimensionTransition;)Lnet/minecraft/world/entity/Entity;", at = @At("HEAD"), cancellable = true)
    private void skein$deferForeignMove(DimensionTransition transition, CallbackInfoReturnable<Entity> cir) {
        Entity self = (Entity) (Object) this;
        if (DimensionTicker.crossesTickingLevel(self.level(), transition.newLevel())
                && DimensionTicker.deferPastBarrier(() -> self.changeDimension(transition))) {
            cir.setReturnValue(self);
        }
    }
}
