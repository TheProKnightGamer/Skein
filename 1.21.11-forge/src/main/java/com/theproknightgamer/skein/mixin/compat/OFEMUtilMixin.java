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
 * Replaces the complete cache lookup; works with both sweepIsClear signatures.
 * With any of the compatibility hooks missing, SpeeDeeEntity keeps its own
 * per-level cache, which is safe while each level has one thread.
 */
@HookFor(Hooks.Feature.IN_LEVEL)
@Mixin(targets = "com.theproknightgamer.speedeeentity.ofem.OFEMUtil", remap = false)
public abstract class OFEMUtilMixin {
    @Inject(method = "tickCache", at = @At("HEAD"), cancellable = true, remap = false)
    private static void skein$threadCache(Level level, CallbackInfoReturnable<Object> cir) {
        if (Hooks.allows(Hooks.Feature.IN_LEVEL)) {
            cir.setReturnValue(SpeeDeeCompat.cacheFor(level));
        }
    }
}
