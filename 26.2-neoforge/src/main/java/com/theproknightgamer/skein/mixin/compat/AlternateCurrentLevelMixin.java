package com.theproknightgamer.skein.mixin.compat;

import com.theproknightgamer.skein.core.AlternateCurrentCompat;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands a parallel pass this thread's own Alternate Current wire handler; see
 * {@link AlternateCurrentCompat}. Priority 1500 so it applies after Alternate
 * Current's own mixin has added the method to {@code ServerLevel}.
 */
@HookFor(Hooks.Feature.IN_LEVEL)
@Mixin(value = ServerLevel.class, priority = 1500)
public abstract class AlternateCurrentLevelMixin implements AlternateCurrentCompat.LevelAccess {

    @Unique
    private volatile AlternateCurrentCompat.Handlers skein$wireHandlers;

    @Override
    public AlternateCurrentCompat.Handlers skein$wireHandlers() {
        return this.skein$wireHandlers;
    }

    @Override
    public void skein$setWireHandlers(AlternateCurrentCompat.Handlers handlers) {
        this.skein$wireHandlers = handlers;
    }

    @Inject(method = "alternate_current$getWireHandler", at = @At("HEAD"), cancellable = true, remap = false)
    private void skein$threadWireHandler(CallbackInfoReturnable<Object> cir) {
        if (TickContext.deferring()) {
            Object own = AlternateCurrentCompat.handlerFor(this);
            if (own != null) {
                cir.setReturnValue(own);
            }
        }
    }
}
