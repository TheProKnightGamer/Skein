package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.EntityTicker;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Consumer;

/**
 * The entity phase.
 *
 * <p>The entity loop is intercepted at {@code entityTickList.forEach} rather
 * than by replacing {@code ServerLevel#tick}, so vanilla's per-entity consumer
 * -- despawn checks, tick freezing, the entity-ticking-range test, passenger
 * handling -- is reused verbatim. Skein only decides which thread each call
 * happens on.
 *
 * <p>Below the default priority for the reason given on {@link ChunkLoopMixin}:
 * another mod's wrapper around the loop runs outside the whole phase, on the
 * server thread, and the serial fallback still goes through anything inside.
 */
@HookFor(Hooks.Feature.ENTITIES)
@Mixin(value = ServerLevel.class, priority = 900)
public abstract class EntityLoopMixin {

    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
    private void skein$tickEntitiesInParallel(EntityTickList list, Consumer<Entity> action, Operation<Void> original) {
        EntityTicker.run((ServerLevel) (Object) this, list, action, () -> original.call(list, action));
    }
}
