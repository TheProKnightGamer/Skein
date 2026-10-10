package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.SkeinConfig;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.EntityTypeTest;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A query for one entity type visits only that type.
 *
 * <p>{@code EntityLookup.getEntities(test, consumer)} is how every
 * {@code @e[type=...]} selector, and every {@code level.getEntities(EntityType,
 * ...)} with no box, finds its entities: it walks every entity in the level and
 * asks each one its type. A datapack that polls for a handful of markers every
 * tick therefore pays for every snowball, item and mob in the dimension --
 * measured at a tenth of a millisecond per selector with 3,600 entities loaded,
 * several selectors a tick, nearly all of it cache misses on entities that were
 * never going to match.
 *
 * <p>So the lookup also files each entity under its type, and a test that is an
 * {@code EntityType} walks only that bucket. Each bucket is insertion-ordered
 * like the id map it mirrors, and every add and remove is applied to both, so
 * a type's entities come out in exactly the relative order the full walk would
 * have produced them -- which matters, because a selector with a limit and no
 * sort takes the first N it is handed. Class-based tests, and anything else
 * that is not a bare entity type, still walk everything.
 */
@Mixin(EntityLookup.class)
public abstract class EntityLookupMixin<T extends EntityAccess> {

    @Shadow @Final private Int2ObjectMap<T> byId;

    @Unique private final Reference2ObjectOpenHashMap<EntityType<?>, Int2ObjectLinkedOpenHashMap<T>> skein$byType =
            new Reference2ObjectOpenHashMap<>();

    @Unique private T skein$displaced;

    @Unique
    private static EntityType<?> skein$typeOf(EntityAccess entity) {
        return entity instanceof Entity e ? e.getType() : null;
    }

    @Unique
    private void skein$unfile(T entity) {
        EntityType<?> type = skein$typeOf(entity);
        if (type == null) {
            return;
        }
        Int2ObjectLinkedOpenHashMap<T> ofType = this.skein$byType.get(type);
        if (ofType != null && ofType.get(entity.getId()) == entity) {
            ofType.remove(entity.getId());
        }
    }

    @Inject(method = "add", at = @At("HEAD"))
    private void skein$rememberDisplaced(T entity, CallbackInfo ci) {
        this.skein$displaced = this.byId.get(entity.getId());
    }

    @Inject(method = "add", at = @At("TAIL"))
    private void skein$fileByType(T entity, CallbackInfo ci) {
        T displaced = this.skein$displaced;
        this.skein$displaced = null;
        // Mirror what vanilla did, not what it was asked: a duplicate UUID is
        // refused, and an id collision replaces whatever held the id.
        if (this.byId.get(entity.getId()) != entity) {
            return;
        }
        if (displaced != null && displaced != entity) {
            skein$unfile(displaced);
        }
        EntityType<?> type = skein$typeOf(entity);
        if (type != null) {
            this.skein$byType.computeIfAbsent(type, t -> new Int2ObjectLinkedOpenHashMap<>()).put(entity.getId(), entity);
        }
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void skein$unfileByType(T entity, CallbackInfo ci) {
        // Vanilla removes whatever holds the id, which is not necessarily the
        // entity it was handed.
        T held = this.byId.get(entity.getId());
        if (held != null) {
            skein$unfile(held);
        }
    }

    @SuppressWarnings("unchecked")
    @Inject(method = "getEntities", at = @At("HEAD"), cancellable = true)
    private <U extends T> void skein$walkOneType(EntityTypeTest<T, U> test, AbortableIterationConsumer<U> consumer,
                                                 CallbackInfo ci) {
        if (!SkeinConfig.Live.enabled || !(test instanceof EntityType<?> type) || test.getClass() != EntityType.class) {
            return;
        }
        ci.cancel();
        Int2ObjectLinkedOpenHashMap<T> ofType = this.skein$byType.get(type);
        if (ofType == null) {
            return;
        }
        for (T entity : ofType.values()) {
            U cast = (U) test.tryCast(entity);
            if (cast != null && consumer.accept(cast).shouldAbort()) {
                return;
            }
        }
    }
}
