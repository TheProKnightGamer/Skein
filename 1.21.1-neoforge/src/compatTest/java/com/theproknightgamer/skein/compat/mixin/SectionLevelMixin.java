package com.theproknightgamer.skein.compat.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.compat.SectionTimes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerLevel.class)
abstract class SectionLevelMixin {
    private boolean skeinTest$recorded() {
        return ((Level) (Object) this).dimension() == Level.OVERWORLD;
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V"))
    private void skeinTest$push(ProfilerFiller profiler, String label, Operation<Void> original) {
        if (skeinTest$recorded()) SectionTimes.push("level", label);
        original.call(profiler, label);
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V"))
    private void skeinTest$popPush(ProfilerFiller profiler, String label, Operation<Void> original) {
        if (skeinTest$recorded()) SectionTimes.popPush("level", label);
        original.call(profiler, label);
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;pop()V"))
    private void skeinTest$pop(ProfilerFiller profiler, Operation<Void> original) {
        if (skeinTest$recorded()) SectionTimes.pop();
        original.call(profiler);
    }
}
