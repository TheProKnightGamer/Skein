package com.theproknightgamer.skein.compat.mixin;

import com.theproknightgamer.skein.compat.DemotionVerification;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The demotion run's cactus, whose random tick fails inside a parallel pass and is counted outside one. */
@Mixin(CactusBlock.class)
abstract class DemotionCactusMixin {
    @Inject(method = "randomTick", at = @At("HEAD"))
    private void skeinTest$throwInPass(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
                                       CallbackInfo ci) {
        if (!DemotionVerification.cactusProbe) return;
        if (TickContext.deferring()) {
            throw new IllegalStateException("demotion probe: random tick inside a parallel pass");
        }
        DemotionVerification.cactusSerialTicks++;
    }
}
