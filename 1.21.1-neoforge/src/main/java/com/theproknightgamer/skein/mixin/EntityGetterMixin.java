package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.CollisionQueries;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.function.Predicate;

/** Marks the entity query of a collision lookup; see {@link CollisionQueries}. */
@HookFor(Hooks.Feature.COLLISION_QUERIES)
@Mixin(EntityGetter.class)
public interface EntityGetterMixin {
    @WrapOperation(method = "getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/EntityGetter;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private List<Entity> skein$markCollisionQuery(EntityGetter self, Entity source, AABB area,
            Predicate<? super Entity> predicate, Operation<List<Entity>> original) {
        return original.call(self, source, area, CollisionQueries.mark(source, predicate));
    }
}
