package com.theproknightgamer.skein.compat.mixin;

import com.theproknightgamer.skein.compat.PhaseTimes;
import com.theproknightgamer.skein.compat.PhaseTimes.Part;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkCache.class)
abstract class TimedChunkCacheMixin {
    @Shadow @Final ServerLevel level;

    private boolean skeinTest$measured() {
        return this.level.dimension() == Level.OVERWORLD;
    }

    @Inject(method = "tickChunks", at = @At("HEAD"))
    private void skeinTest$walk(CallbackInfo ci) {
        if (skeinTest$measured()) PhaseTimes.begin(Part.CHUNK_WALK);
    }

    @Inject(method = "tickChunks", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/NaturalSpawner;createState(ILjava/lang/Iterable;Lnet/minecraft/world/level/NaturalSpawner$ChunkGetter;Lnet/minecraft/world/level/LocalMobCapCalculator;)Lnet/minecraft/world/level/NaturalSpawner$SpawnState;"))
    private void skeinTest$census(CallbackInfo ci) {
        if (skeinTest$measured()) PhaseTimes.next(Part.CHUNK_WALK, Part.CHUNK_CENSUS);
    }

    @Inject(method = "tickChunks", at = @At(value = "INVOKE", target = "Lnet/minecraft/Util;shuffle(Ljava/util/List;Lnet/minecraft/util/RandomSource;)V"))
    private void skeinTest$loop(CallbackInfo ci) {
        if (skeinTest$measured()) PhaseTimes.next(Part.CHUNK_CENSUS, Part.CHUNK_LOOP);
    }

    @Inject(method = "tickChunks", at = @At(value = "INVOKE", target = "Ljava/util/List;forEach(Ljava/util/function/Consumer;)V"))
    private void skeinTest$broadcast(CallbackInfo ci) {
        if (skeinTest$measured()) PhaseTimes.next(Part.CHUNK_LOOP, Part.CHUNK_BROADCAST);
    }

    @Inject(method = "tickChunks", at = @At("RETURN"))
    private void skeinTest$done(CallbackInfo ci) {
        if (skeinTest$measured()) PhaseTimes.end(Part.CHUNK_BROADCAST);
    }
}
