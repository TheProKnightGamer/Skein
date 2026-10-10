package com.theproknightgamer.skein.compat.mixin;

import com.theproknightgamer.skein.compat.DemotionVerification;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The demotion run's furnace, which fails inside a parallel pass and counts its ticks outside one. */
@Mixin(AbstractFurnaceBlockEntity.class)
abstract class DemotionFurnaceMixin {
    @Inject(method = "serverTick", at = @At("HEAD"))
    private static void skeinTest$throwInPass(ServerLevel level, BlockPos pos, BlockState state,
                                                 AbstractFurnaceBlockEntity furnace, CallbackInfo ci) {
        BlockPos probe = DemotionVerification.probeFurnace;
        if (probe == null || !probe.equals(pos)) return;
        if (TickContext.deferring()) {
            throw new IllegalStateException("demotion probe: ticked inside a parallel pass");
        }
        DemotionVerification.furnaceSerialTicks++;
    }
}
