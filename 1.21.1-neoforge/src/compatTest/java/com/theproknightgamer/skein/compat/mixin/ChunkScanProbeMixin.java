package com.theproknightgamer.skein.compat.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.theproknightgamer.skein.compat.ChunkScanVerification;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ServerChunkCache.class)
abstract class ChunkScanProbeMixin {
    @Shadow @Final ServerLevel level;
    @Shadow @Final public ChunkMap chunkMap;

    @Unique private List<?> skeinTest$list;

    @Inject(method = "tickChunks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/Util;shuffle(Ljava/util/List;Lnet/minecraft/util/RandomSource;)V"))
    private void skeinTest$checkWalk(CallbackInfo ci, @Local(ordinal = 0) List<?> list) {
        if (!ChunkScanVerification.active(this.level)) return;
        ChunkScanVerification.owner((ServerChunkCache) (Object) this);
        ChunkScanVerification.checkWalk(this.chunkMap, list);
        this.skeinTest$list = list;
    }

    @Inject(method = "tickChunks", at = @At("RETURN"))
    private void skeinTest$checkBroadcast(CallbackInfo ci) {
        List<?> list = this.skeinTest$list;
        this.skeinTest$list = null;
        if (list != null) ChunkScanVerification.checkBroadcast(list);
    }
}
