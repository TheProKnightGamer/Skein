package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.DimensionTicker;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The vanilla entities that override {@code teleport} to do something around the
 * move -- an item merges with its new neighbours, falling sand notes the End crossing, a pearl places a chunk ticket, a villager stops trading. {@link EntityMixin} parks a move made from a level
 * the caller is not ticking at the head of the base method, and hands the
 * entity back; an override would then carry on with its own part, on the
 * caller's thread, against a level another thread is ticking, and do it a
 * second time when the parked move runs. So these park at their own head:
 * the whole override waits for the dimension barrier.
 */
@HookFor(Hooks.Feature.DIMENSIONS)
@Mixin({ItemEntity.class, FallingBlockEntity.class, PrimedTnt.class, AbstractVillager.class, ThrownEnderpearl.class})
public abstract class TeleportOverridesMixin {

    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/world/entity/Entity;", at = @At("HEAD"), cancellable = true)
    private void skein$deferForeignMove(TeleportTransition transition, CallbackInfoReturnable<Entity> cir) {
        Entity self = (Entity) (Object) this;
        if (DimensionTicker.crossesTickingLevel(self.level(), transition.newLevel())
                && DimensionTicker.deferPastBarrier(() -> self.teleport(transition))) {
            cir.setReturnValue(self);
        }
    }
}
