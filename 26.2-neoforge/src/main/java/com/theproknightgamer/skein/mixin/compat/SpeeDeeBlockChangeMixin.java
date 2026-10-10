package com.theproknightgamer.skein.mixin.compat;

import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.SpeeDeeCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@HookFor(Hooks.Feature.IN_LEVEL)
@Mixin(LevelChunk.class)
public abstract class SpeeDeeBlockChangeMixin {
    @Shadow public abstract Level getLevel();

    // Bracket writes so a cache used during callbacks, or on another worker,
    // cannot keep an answer across the write. Also covers direct chunk writes,
    // beyond SpeeDeeEntity's own Level.setBlock invalidation.
    // By descriptor: WorldEdit's four-argument overload calls this one.
    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
            at = { @At("HEAD"), @At("RETURN") })
    private void skein$invalidateClearance(BlockPos pos, BlockState state, int flags,
                                           CallbackInfoReturnable<BlockState> cir) {
        ((SpeeDeeCompat.LevelAccess) getLevel()).skein$speedeeCaches().invalidate();
    }
}
