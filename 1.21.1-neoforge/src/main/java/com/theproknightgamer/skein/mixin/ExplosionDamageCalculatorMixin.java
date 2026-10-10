package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.ExplosionRays;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The damage half of an explosion's exposure asks; see {@link ExplosionRays.Exposure}. */
@HookFor(Hooks.Feature.EXPLOSIONS)
@Mixin(ExplosionDamageCalculator.class)
public abstract class ExplosionDamageCalculatorMixin {
    @WrapOperation(method = "getEntityDamageAmount", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Explosion;getSeenPercent(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/Entity;)F"))
    private float skein$exposedDamage(Vec3 centre, Entity entity, Operation<Float> original, Explosion explosion) {
        ExplosionRays.Exposure exposure = explosion instanceof ExplosionRays.Exposed exposed ? exposed.skein$exposure() : null;
        float seen = exposure == null ? Float.NaN : exposure.lookup(entity.level(), centre, entity);
        return Float.isNaN(seen) ? original.call(centre, entity) : seen;
    }
}
