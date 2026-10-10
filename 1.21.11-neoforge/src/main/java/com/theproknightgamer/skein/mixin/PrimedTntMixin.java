package com.theproknightgamer.skein.mixin;

import org.spongepowered.asm.mixin.Shadow;
import com.theproknightgamer.skein.core.SkeinExplosive;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.SkeinLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.PrimedTnt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A one-tick scheduling hint; fuse, movement, explosion and ordering stay
 * untouched. Also exposes the TNT's own explosion power -- up to 128 through
 * NBT -- so a charge whose blast can reach past the loaded margin ticks on the
 * level thread; see {@link SkeinExplosive}.
 */
@HookFor({})
@Mixin(PrimedTnt.class)
public abstract class PrimedTntMixin implements SkeinExplosive {
    @Shadow private float explosionPower;

    @Override
    public float skein$explosionRadius() {
        return this.explosionPower;
    }

    @Inject(method = "setFuse", at = @At("RETURN"))
    private void skein$announceShortFuse(int fuse, CallbackInfo ci) {
        // Commands/NBT can create TNT that explodes on its first tick. It never
        // passes through the fuse=2 countdown hook before the phase decision.
        if (fuse <= 1) skein$requestProbe();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void skein$announceDetonation(CallbackInfo ci) {
        PrimedTnt tnt = (PrimedTnt) (Object) this;
        if (tnt.getFuse() == 2) skein$requestProbe();
    }

    private void skein$requestProbe() {
        PrimedTnt tnt = (PrimedTnt) (Object) this;
        if (SkeinConfig.Live.enabled && SkeinConfig.Live.adaptive
                && tnt.level() instanceof ServerLevel level) {
            ((SkeinLevel) level).skein$governor().requestEntityBurstProbe();
        }
    }
}
