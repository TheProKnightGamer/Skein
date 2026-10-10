package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.PistonPushes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * A large push spread over the pool; see {@link PistonPushes}. The loop itself is
 * vanilla's: only the two per-entity calls inside it are collected, and run at
 * its end. If another mod changes anything between the entity lookup and the
 * end of the method, the feature steps aside and the loop runs as that mod made it.
 */
@Mixin(PistonMovingBlockEntity.class)
public abstract class PistonMovingBlockEntityMixin {

    @HookFor(value = Hooks.Feature.PISTON_PUSHES, yieldsUntil = "skein$flushPushes")
    @WrapOperation(method = "moveCollidedEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private static List<Entity> skein$beginPushes(Level level, Entity except, AABB area,
                                                  Operation<List<Entity>> original) {
        List<Entity> entities = original.call(level, except, area);
        PistonPushes.begin(level, entities);
        return entities;
    }

    @HookFor(Hooks.Feature.PISTON_PUSHES)
    @WrapOperation(method = "moveCollidedEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/piston/PistonMovingBlockEntity;moveEntityByPiston(Lnet/minecraft/core/Direction;Lnet/minecraft/world/entity/Entity;DLnet/minecraft/core/Direction;)V"))
    private static void skein$collectMove(Direction pistonDirection, Entity entity, double delta, Direction movement,
                                          Operation<Void> original) {
        if (!PistonPushes.collect(entity, () -> original.call(pistonDirection, entity, delta, movement))) {
            original.call(pistonDirection, entity, delta, movement);
        }
    }

    @HookFor(Hooks.Feature.PISTON_PUSHES)
    @WrapOperation(method = "moveCollidedEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/piston/PistonMovingBlockEntity;fixEntityWithinPistonBase(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Direction;D)V"))
    private static void skein$collectFix(BlockPos pos, Entity entity, Direction direction, double deltaProgress,
                                         Operation<Void> original) {
        if (!PistonPushes.collect(entity, () -> original.call(pos, entity, direction, deltaProgress))) {
            original.call(pos, entity, direction, deltaProgress);
        }
    }

    @HookFor(Hooks.Feature.PISTON_PUSHES)
    @Inject(method = "moveCollidedEntities", at = @At("RETURN"))
    private static void skein$flushPushes(CallbackInfo ci) {
        PistonPushes.flush();
    }
}
