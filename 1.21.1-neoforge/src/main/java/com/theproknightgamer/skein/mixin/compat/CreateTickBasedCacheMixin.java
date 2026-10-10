package com.theproknightgamer.skein.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.google.common.collect.ImmutableMap;
import com.theproknightgamer.skein.core.CreateCompat;
import com.theproknightgamer.skein.core.HookFor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Create's timed caches -- the logistics link and stock summaries among them
 * -- are static, server-wide, and keep their timestamps in a plain
 * {@code HashMap} written on every lookup. See {@link CreateCompat}.
 */
@HookFor({})
@Pseudo
@Mixin(targets = "com.simibubi.create.foundation.utility.TickBasedCache", remap = false)
public abstract class CreateTickBasedCacheMixin {

    @WrapMethod(method = "getIfPresent")
    private Object skein$getIfPresent(Object key, Operation<Object> original) {
        synchronized (CreateCompat.LOCK) {
            return original.call(key);
        }
    }

    @WrapMethod(method = "get(Ljava/lang/Object;Ljava/util/concurrent/Callable;)Ljava/lang/Object;")
    private Object skein$get(Object key, Callable<?> loader, Operation<Object> original) {
        synchronized (CreateCompat.LOCK) {
            return original.call(key, loader);
        }
    }

    @WrapMethod(method = "getAllPresent")
    private ImmutableMap<?, ?> skein$getAllPresent(Iterable<?> keys, Operation<ImmutableMap<?, ?>> original) {
        synchronized (CreateCompat.LOCK) {
            return original.call(keys);
        }
    }

    @WrapMethod(method = "put")
    private void skein$put(Object key, Object value, Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call(key, value);
        }
    }

    @WrapMethod(method = "putAll")
    private void skein$putAll(Map<?, ?> values, Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call(values);
        }
    }

    @WrapMethod(method = "invalidate")
    private void skein$invalidate(Object key, Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call(key);
        }
    }

    @WrapMethod(method = "invalidateAll(Ljava/lang/Iterable;)V")
    private void skein$invalidateSome(Iterable<?> keys, Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call(keys);
        }
    }

    @WrapMethod(method = "invalidateAll()V")
    private void skein$invalidateAll(Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call();
        }
    }

    @WrapMethod(method = "cleanUp")
    private void skein$cleanUp(Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call();
        }
    }
}
