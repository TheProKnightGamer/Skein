package com.theproknightgamer.skein.compat.mixin;

import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A mod that hooks the entity-tracking loop inside the stretch Skein would run
 * on the pool. Skein must leave tracking on the loop so this still runs for
 * every entity.
 */
@Mixin(value = ChunkMap.class, priority = 1000)
abstract class ConflictTrackingMixin {
    @Inject(method = "tick()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerEntity;sendChanges()V"))
    private void skeinTest$beforeSendChanges(CallbackInfo ci) {
    }
}
