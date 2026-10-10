package com.theproknightgamer.skein.compat.mixin;

import com.theproknightgamer.skein.compat.DemotionVerification;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The demotion run's armor stand, which fails whenever it ticks inside a parallel pass. */
@Mixin(LivingEntity.class)
abstract class DemotionProbeMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void skeinTest$throwInPass(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        // Inside a parallel pass is where code that is not safe to share fails:
        // with dimensions threaded, the level's own thread is a worker too.
        if (self.getTags().contains(DemotionVerification.THROW_TAG) && TickContext.deferring()) {
            throw new IllegalStateException("demotion probe: ticked inside a parallel pass");
        }
    }
}
