package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.CollisionQueries;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Opens a collision lookup for {@link CollisionQueries} from {@code Entity.collide}:
 * the entity is noted on this thread for exactly the entity query of its own
 * lookup, which {@code LevelMixin} takes. Newer versions mark the query inside
 * {@code EntityGetter.getEntityCollisions}; this version's mixin library cannot
 * inject into an interface's default method.
 */
@HookFor(Hooks.Feature.COLLISION_QUERIES)
@Mixin(Entity.class)
public abstract class EntityCollisionQueryMixin {
    @WrapOperation(method = "collide", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private List<VoxelShape> skein$openCollisionQuery(Level level, Entity source, AABB area,
                                                      Operation<List<VoxelShape>> original) {
        if (!CollisionQueries.marks(source)) {
            return original.call(level, source, area);
        }
        TickContext.Local local = TickContext.local();
        Entity outer = local.collisionSource;
        local.collisionSource = source;
        try {
            return original.call(level, source, area);
        } finally {
            local.collisionSource = outer;
        }
    }
}
