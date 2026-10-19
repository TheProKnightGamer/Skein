package com.theproknightgamer.skein.mixin.compat;

import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.SpeeDeeCompat;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SpeeDeeEntity 1.0.2 reaches the level's TickCache directly, not only through
 * {@code OFEMUtil.tickCache} ({@link OFEMUtilMixin}): its shared move answers
 * ({@code MoveMemo}), its air-region chunk memo ({@code AirRegion}) and its
 * entity-collision hook each ask the level for the one cache, so every worker
 * moving an entity read and wrote the same memo fields. This hands every caller
 * the thread's own cache, the one {@code OFEMUtil} already gets; each memo
 * entry carries its tick and block epoch inside that cache, so a thread's cache
 * is complete on its own.
 *
 * <p>Priority 1500 so it applies after SpeeDeeEntity's own mixin has added the
 * method to {@code Level}.
 */
@HookFor(Hooks.Feature.IN_LEVEL)
@Mixin(value = Level.class, priority = 1500)
public abstract class SpeeDeeCacheHolderMixin {
    @Inject(method = "speedee$tickCache", at = @At("HEAD"), cancellable = true, remap = false)
    private void skein$threadTickCache(CallbackInfoReturnable<Object> cir) {
        if (Hooks.allows(Hooks.Feature.IN_LEVEL)) {
            cir.setReturnValue(SpeeDeeCompat.cacheFor((Level) (Object) this));
        }
    }
}
