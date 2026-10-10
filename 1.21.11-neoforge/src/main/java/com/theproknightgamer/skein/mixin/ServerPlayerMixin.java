package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.DimensionTicker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.HookFor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * The player's own version of the teleport {@link EntityMixin} parks, since
 * {@code ServerPlayer} overrides it. A player leaving a level from another
 * level's tick -- an ender pearl it threw through a portal, a command block's
 * {@code /tp} -- is taken out of that level's entity manager, chunk tracking
 * and player list while its own thread may be in them. The whole teleport
 * waits for the dimension barrier; the caller gets the player back.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    // With its descriptor: javac also emits a bridge returning Entity, and a
    // bare name could land on the bridge, which callers typed as ServerPlayer
    // never go through.
    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;",
            at = @At("HEAD"), cancellable = true)
    private void skein$deferForeignTeleport(TeleportTransition transition, CallbackInfoReturnable<ServerPlayer> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (DimensionTicker.crossesTickingLevel(self.level(), transition.newLevel())
                && DimensionTicker.deferPastBarrier(() -> self.teleport(transition))) {
            cir.setReturnValue(self);
        }
    }

    /**
     * {@code teleportTo} wakes a sleeping player -- a write to the bed in the
     * player's level -- and resets the camera before it reaches
     * {@code teleport}, so it waits whole as well.
     */
    @HookFor(Hooks.Feature.DIMENSIONS)
    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z",
            at = @At("HEAD"), cancellable = true)
    private void skein$deferForeignTeleportTo(ServerLevel level, double x, double y, double z,
                                              Set<Relative> relative, float yRot, float xRot, boolean setCamera,
                                              CallbackInfoReturnable<Boolean> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (DimensionTicker.crossesTickingLevel(self.level(), level) && DimensionTicker.deferPastBarrier(
                () -> self.teleportTo(level, x, y, z, relative, yRot, xRot, setCamera))) {
            cir.setReturnValue(true);
        }
    }
}
