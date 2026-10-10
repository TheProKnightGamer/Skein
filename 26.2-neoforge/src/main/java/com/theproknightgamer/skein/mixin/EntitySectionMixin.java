package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.TickContext;
import com.theproknightgamer.skein.core.CollisionQueries;
import com.theproknightgamer.skein.core.ProjectileQueries;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Registry teardown waits for the barrier; query visibility must not. */
@Mixin(EntitySection.class)
public abstract class EntitySectionMixin {
    @Shadow @Final private ClassInstanceMultiMap<EntityAccess> storage;

    /** Immutable after publication. Structural edits happen outside worker passes. */
    @Unique private volatile List<EntityAccess> skein$projectileTargets;

    /**
     * The members a collision query can find, in section order; see
     * {@link CollisionQueries}. Kept up to date as members come and go rather
     * than rebuilt on demand: a pushed stack of TNT crossing into this section
     * arrives one entity at a time, and rebuilding after each would put the
     * stack's square back. A TNT is never on it, so its arrivals cost nothing.
     * Immutable after publication, written only where structural edits happen.
     */
    @Unique private volatile List<EntityAccess> skein$colliders = List.of();

    @HookFor(Hooks.Feature.PROJECTILE_QUERIES)
    @Inject(method = "add", at = {@At("HEAD"), @At("RETURN")})
    private void skein$invalidateAdded(EntityAccess entity, CallbackInfo ci) {
        this.skein$projectileTargets = null;
    }

    @HookFor(Hooks.Feature.PROJECTILE_QUERIES)
    @Inject(method = "remove", at = {@At("HEAD"), @At("RETURN")})
    private void skein$invalidateRemoved(EntityAccess entity, CallbackInfoReturnable<Boolean> cir) {
        this.skein$projectileTargets = null;
    }

    @HookFor(Hooks.Feature.COLLISION_QUERIES)
    @Inject(method = "add", at = @At("RETURN"))
    private void skein$colliderAdded(EntityAccess entity, CallbackInfo ci) {
        if (CollisionQueries.candidate(entity)) {
            List<EntityAccess> before = this.skein$colliders;
            ArrayList<EntityAccess> after = new ArrayList<>(before.size() + 1);
            after.addAll(before);
            after.add(entity);
            this.skein$colliders = List.copyOf(after);
        }
    }

    @HookFor(Hooks.Feature.COLLISION_QUERIES)
    @Inject(method = "remove", at = @At("RETURN"))
    private void skein$colliderRemoved(EntityAccess entity, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && CollisionQueries.candidate(entity)) {
            List<EntityAccess> before = this.skein$colliders;
            ArrayList<EntityAccess> after = new ArrayList<>(before);
            for (int i = 0; i < after.size(); i++) {
                if (after.get(i) == entity) {
                    after.remove(i);
                    break;
                }
            }
            this.skein$colliders = List.copyOf(after);
        }
    }

    @HookFor({Hooks.Feature.PROJECTILE_QUERIES, Hooks.Feature.COLLISION_QUERIES})
    @WrapOperation(method =
            "getEntities(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/AbortableIterationConsumer$Continuation;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ClassInstanceMultiMap;iterator()Ljava/util/Iterator;"))
    private Iterator<EntityAccess> skein$projectileIterator(ClassInstanceMultiMap<EntityAccess> storage,
            Operation<Iterator<EntityAccess>> original,
            @Local(argsOnly = true) AbortableIterationConsumer<EntityAccess> consumer) {
        if (consumer instanceof CollisionQueries.Targets<?>) {
            // Only members that could pass the query's own predicate, in section order.
            return this.skein$colliders.iterator();
        }
        if (!(consumer instanceof ProjectileQueries.Targets<?>)) return original.call(storage);
        List<EntityAccess> targets = this.skein$projectileTargets;
        if (targets == null) {
            // Multiple readers may build the same list on the first query.
            // The source cannot change while workers run, so either result is
            // valid. No global index, lock, or main-thread preprocessing.
            ArrayList<EntityAccess> entries = new ArrayList<>();
            for (EntityAccess entity : this.storage) {
                if (!ProjectileQueries.ordinaryThrowable(entity)) entries.add(entity);
            }
            targets = List.copyOf(entries);
            this.skein$projectileTargets = targets;
        }
        // Keep vanilla section/entry order and the original bounds, predicate,
        // removal and consumer code. Eligibility and positions are never cached.
        return targets.iterator();
    }

    @HookFor(Hooks.Feature.IN_LEVEL)
    @ModifyExpressionValue(method =
            "getEntities(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/AbortableIterationConsumer$Continuation;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/AABB;intersects(Lnet/minecraft/world/phys/AABB;)Z"))
    private boolean skein$visibleUntyped(boolean intersects, @Local EntityAccess candidate) {
        return intersects && skein$visible(candidate);
    }

    @HookFor(Hooks.Feature.IN_LEVEL)
    @ModifyExpressionValue(method =
            "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/AbortableIterationConsumer$Continuation;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/AABB;intersects(Lnet/minecraft/world/phys/AABB;)Z"))
    private boolean skein$visibleTyped(boolean intersects, @Local(ordinal = 1) EntityAccess candidate) {
        return intersects && skein$visible(candidate);
    }

    @Unique
    private static boolean skein$visible(EntityAccess candidate) {
        // Live entities are overwhelmingly common. Keep their path to one
        // removal check, with no context lookup or wrapped consumer call.
        // Vanilla would already have unlinked a removed entity; Skein hides it
        // until teardown can safely run at the barrier.
        return !(candidate instanceof Entity entity) || !entity.isRemoved() || !TickContext.deferring();
    }
}
