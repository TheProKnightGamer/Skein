package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.theproknightgamer.skein.core.CollisionQueries;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Markers that do nothing, around the bodies of {@code Entity}'s two collision
 * rules. {@link CollisionQueries} reads those rules off each entity's class --
 * a class that keeps {@code canBeCollidedWith} is never collided with -- which is
 * only true while nobody has changed the methods on {@code Entity} itself. If
 * another mod injects between these markers or overwrites either method, the
 * hook audit switches the collision shortcut off and both rules run as that mod
 * made them. Applied last, so the opening marker is the first thing in each method.
 */
@Mixin(value = Entity.class, priority = 10000)
public abstract class EntityCollisionRuleMixin {
    @HookFor(value = {}, yieldsUntil = "skein$collidedWithEnd", yields = Hooks.Feature.COLLISION_QUERIES)
    @Inject(method = "canBeCollidedWith", at = @At("HEAD"))
    private void skein$collidedWithStart(Entity other, CallbackInfoReturnable<Boolean> cir) {
    }

    @HookFor({})
    @ModifyReturnValue(method = "canBeCollidedWith", at = @At("RETURN"))
    private boolean skein$collidedWithEnd(boolean value) {
        return value;
    }

    @HookFor(value = {}, yieldsUntil = "skein$collideWithEnd", yields = Hooks.Feature.COLLISION_QUERIES)
    @Inject(method = "canCollideWith", at = @At("HEAD"))
    private void skein$collideWithStart(Entity other, CallbackInfoReturnable<Boolean> cir) {
    }

    @HookFor({})
    @ModifyReturnValue(method = "canCollideWith", at = @At("RETURN"))
    private boolean skein$collideWithEnd(boolean value) {
        return value;
    }
}
