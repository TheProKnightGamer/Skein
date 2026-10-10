package com.theproknightgamer.skein.compat.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.compat.SectionTimes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MinecraftServer.class)
abstract class SectionServerMixin {
    @WrapOperation(method = "tickChildren", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V"))
    private void skeinTest$push(ProfilerFiller profiler, String label, Operation<Void> original) {
        SectionTimes.push("server", label);
        original.call(profiler, label);
    }

    @WrapOperation(method = "tickChildren", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V"))
    private void skeinTest$popPush(ProfilerFiller profiler, String label, Operation<Void> original) {
        SectionTimes.popPush("server", label);
        original.call(profiler, label);
    }

    @WrapOperation(method = "tickChildren", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;pop()V"))
    private void skeinTest$pop(ProfilerFiller profiler, Operation<Void> original) {
        SectionTimes.pop();
        original.call(profiler);
    }
}
