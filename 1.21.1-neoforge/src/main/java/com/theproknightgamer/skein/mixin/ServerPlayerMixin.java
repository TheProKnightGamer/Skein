package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.DimensionTicker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * A player's own versions of the cross-level moves {@link EntityMixin} parks:
 * {@code ServerPlayer} overrides both. A player leaving a level from another
 * level's tick -- an ender pearl it threw through a portal, a command block's
 * {@code /tp} -- is taken out of that level's entity manager, chunk tracking
 * and player list while its own thread may be in them. The whole move waits
 * for the dimension barrier; vanilla hands the player back either way.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    @Inject(method = "changeDimension", at = @At("HEAD"), cancellable = true)
    private void skein$deferForeignTravel(DimensionTransition transition, CallbackInfoReturnable<Entity> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (DimensionTicker.crossesTickingLevel(self.level(), transition.newLevel())
                && DimensionTicker.deferPastBarrier(() -> self.changeDimension(transition))) {
            cir.setReturnValue(self);
        }
    }

    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z",
            at = @At("HEAD"), cancellable = true)
    private void skein$deferForeignTeleport(ServerLevel level, double x, double y, double z,
                                            Set<RelativeMovement> relative, float yRot, float xRot,
                                            CallbackInfoReturnable<Boolean> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (DimensionTicker.crossesTickingLevel(self.level(), level)
                && DimensionTicker.deferPastBarrier(() -> self.teleportTo(level, x, y, z, relative, yRot, xRot))) {
            cir.setReturnValue(true);
        }
    }
}
