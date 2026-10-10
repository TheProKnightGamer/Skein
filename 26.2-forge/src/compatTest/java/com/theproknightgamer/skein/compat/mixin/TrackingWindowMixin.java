package com.theproknightgamer.skein.compat.mixin;

import com.theproknightgamer.skein.compat.TrackingVerification;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Opens and closes {@link TrackingVerification}'s recording window around entity tracking. */
@Mixin(ChunkMap.class)
abstract class TrackingWindowMixin {
    @Shadow @Final ServerLevel level;

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void skeinTest$begin(CallbackInfo ci) {
        if (this.level.dimension() == Level.OVERWORLD) TrackingVerification.window((ChunkMap) (Object) this, true);
    }

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void skeinTest$end(CallbackInfo ci) {
        if (this.level.dimension() == Level.OVERWORLD) TrackingVerification.window((ChunkMap) (Object) this, false);
    }
}
