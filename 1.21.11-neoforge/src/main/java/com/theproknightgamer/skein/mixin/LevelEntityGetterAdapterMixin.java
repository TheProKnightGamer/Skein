package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.CollisionQueries;
import com.theproknightgamer.skein.core.ProjectileQueries;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Consumer;

@HookFor({Hooks.Feature.PROJECTILE_QUERIES, Hooks.Feature.COLLISION_QUERIES})
@Mixin(LevelEntityGetterAdapter.class)
public abstract class LevelEntityGetterAdapterMixin {
    @WrapOperation(method = "get(Lnet/minecraft/world/phys/AABB;Ljava/util/function/Consumer;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/AbortableIterationConsumer;forConsumer(Ljava/util/function/Consumer;)Lnet/minecraft/util/AbortableIterationConsumer;"))
    private <T> AbortableIterationConsumer<T> skein$carryQueryMarker(Consumer<T> consumer,
            Operation<AbortableIterationConsumer<T>> original) {
        if (consumer instanceof ProjectileQueries.Candidates<T> marked) {
            return new ProjectileQueries.Targets<>(marked.original());
        }
        if (consumer instanceof CollisionQueries.Candidates<T> marked) {
            return new CollisionQueries.Targets<>(marked.original());
        }
        return original.call(consumer);
    }
}
