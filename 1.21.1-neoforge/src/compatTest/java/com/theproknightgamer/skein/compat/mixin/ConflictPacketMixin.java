package com.theproknightgamer.skein.compat.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** A mod that @Redirects the very call Skein wraps; both must apply. */
@Mixin(value = ServerCommonPacketListenerImpl.class, priority = 1000)
abstract class ConflictPacketMixin {
    @Redirect(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;isSameThread()Z"))
    private boolean skeinTest$conflictSameThread(MinecraftServer server) {
        return server.isSameThread();
    }
}
