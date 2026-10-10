package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import net.minecraft.util.ClassInstanceMultiMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one place where <em>reading</em> the entity index writes to it.
 *
 * <p>Every {@code EntitySection} holds one of these, and every area query goes
 * through {@code find(Class)} -- which caches the per-class sub-list the first
 * time that class is asked for, with {@code computeIfAbsent} on a plain
 * {@code HashMap}. Skein makes the entity registry read-only for the duration
 * of a pass and defers every structural write, and that is not enough here,
 * because this write does not come from a structural change. It comes from a
 * mob looking for something.
 *
 * <p>Two mobs in different cells can query the same section: writes are bounded
 * by the margin, reads are not -- an area query from one cell reaches into the
 * next. So two threads land in {@code computeIfAbsent} on the same map, and a
 * {@code HashMap} answers that with a {@code ConcurrentModificationException}
 * if you are lucky and a corrupted bucket chain if you are not.
 *
 * <p>A concurrent map fixes it outright. The list it caches is built from
 * {@code allInstances}, which really is read-only during a pass, and the
 * initial mapping is copied across so the base-class entry stays the same list
 * object that {@code add} and {@code remove} append to.
 */
@HookFor(Hooks.Feature.IN_LEVEL)
@SuppressWarnings({"rawtypes", "unchecked"})
@Mixin(ClassInstanceMultiMap.class)
public abstract class ClassInstanceMultiMapMixin {

    @Shadow @Final @Mutable private Map<Class<?>, List> byClass;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void skein$concurrentClassIndex(Class baseClass, CallbackInfo ci) {
        this.byClass = new ConcurrentHashMap<>(this.byClass);
    }
}
