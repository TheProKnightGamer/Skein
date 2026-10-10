package com.theproknightgamer.skein.compat.mixin;

import com.theproknightgamer.skein.compat.PhaseTimes;
import com.theproknightgamer.skein.compat.PhaseTimes.Part;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
abstract class TimedServerMixin {
    @Inject(method = "tickChildren", at = @At("HEAD"))
    private void skeinTest$children(BooleanSupplier time, CallbackInfo ci) {
        PhaseTimes.begin(Part.CHILDREN);
    }

    @Inject(method = "tickChildren", at = @At("RETURN"))
    private void skeinTest$childrenDone(BooleanSupplier time, CallbackInfo ci) {
        PhaseTimes.end(Part.CHILDREN);
    }

    @Inject(method = "tickChildren", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/ServerFunctionManager;tick()V"))
    private void skeinTest$functions(BooleanSupplier time, CallbackInfo ci) {
        PhaseTimes.begin(Part.FUNCTIONS);
    }

    @Inject(method = "tickChildren", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/ServerFunctionManager;tick()V", shift = At.Shift.AFTER))
    private void skeinTest$functionsDone(BooleanSupplier time, CallbackInfo ci) {
        PhaseTimes.end(Part.FUNCTIONS);
    }

    @Inject(method = "tickChildren", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/PlayerChunkSender;sendNextChunks(Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void skeinTest$send(BooleanSupplier time, CallbackInfo ci) {
        PhaseTimes.begin(Part.CHUNK_SEND);
    }

    @Inject(method = "tickChildren", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/PlayerChunkSender;sendNextChunks(Lnet/minecraft/server/level/ServerPlayer;)V", shift = At.Shift.AFTER))
    private void skeinTest$sendDone(BooleanSupplier time, CallbackInfo ci) {
        PhaseTimes.end(Part.CHUNK_SEND);
    }
}
