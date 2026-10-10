package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.DeferredWork;
import com.theproknightgamer.skein.core.Demotions;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.SkeinRefusal;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Random ticks of a block or fluid that is not safe to tick in parallel.
 *
 * <p>The random-tick phase splits by chunk, so a failure there names a chunk,
 * not the block whose code failed, and {@link Demotions} cannot send a chunk
 * back. These wrap the two calls in {@code tickChunk} that hand a block or a
 * fluid its random tick. One that throws inside a pass is demoted: that tick is
 * dropped, as it was going to fail, and from then on its random ticks wait for
 * the end of the pass and run on the coordinator, after re-reading the block,
 * because something else in the pass may have replaced it in the meantime.
 *
 * <p>A refusal ends just the one random tick here, and while a pass runs the
 * block or fluid being ticked is named for the stall report. Priority 100 for
 * the reason given on {@link TickGuardMixin}: Neruina wraps the same call, and
 * would otherwise take a refusal for a broken block and stop its random ticks.
 *
 * <p>Optional: without these, a throwing random tick is handled as before.
 */
@Mixin(value = ServerLevel.class, priority = 100)
public abstract class RandomTickFallbackMixin {

    @HookFor({})
    @WrapOperation(method = "tickChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;randomTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private void skein$blockRandomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
                                       Operation<Void> original) {
        if (!TickContext.deferring()) {
            original.call(state, level, pos, random);
            return;
        }
        Block block = state.getBlock();
        if (Demotions.block(block)) {
            BlockPos at = pos.immutable();
            boolean parked = DeferredWork.park(() -> {
                BlockState now = level.getBlockState(at);
                if (now.getBlock() == block && now.isRandomlyTicking()) {
                    original.call(now, level, at, random);
                }
            });
            if (!parked) {
                original.call(state, level, pos, random);
            }
            return;
        }
        TickContext.Local local = TickContext.local();
        Object outer = local.ticking;
        local.ticking = block;
        try {
            original.call(state, level, pos, random);
        } catch (RuntimeException | Error thrown) {
            if (SkeinRefusal.in(thrown)) {
                TickContext.resetBrackets();
                SkeinRefusal.swallowed(block, thrown);
                return;
            }
            if (SkeinConfig.Live.crashPolicy == SkeinConfig.CrashPolicy.RETHROW) {
                throw thrown;
            }
            TickContext.resetBrackets();
            Demotions.blockThrew(block, level, thrown);
        } finally {
            local.ticking = outer;
        }
    }

    @HookFor({})
    @WrapOperation(method = "tickChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/material/FluidState;randomTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private void skein$fluidRandomTick(FluidState state, Level level, BlockPos pos, RandomSource random,
                                       Operation<Void> original) {
        if (!TickContext.deferring() || !(level instanceof ServerLevel serverLevel)) {
            original.call(state, level, pos, random);
            return;
        }
        Fluid fluid = state.getType();
        if (Demotions.fluid(fluid)) {
            BlockPos at = pos.immutable();
            boolean parked = DeferredWork.park(() -> {
                FluidState now = level.getFluidState(at);
                if (now.getType() == fluid && now.isRandomlyTicking()) {
                    original.call(now, level, at, random);
                }
            });
            if (!parked) {
                original.call(state, level, pos, random);
            }
            return;
        }
        TickContext.Local local = TickContext.local();
        Object outer = local.ticking;
        local.ticking = fluid;
        try {
            original.call(state, level, pos, random);
        } catch (RuntimeException | Error thrown) {
            if (SkeinRefusal.in(thrown)) {
                TickContext.resetBrackets();
                SkeinRefusal.swallowed(fluid, thrown);
                return;
            }
            if (SkeinConfig.Live.crashPolicy == SkeinConfig.CrashPolicy.RETHROW) {
                throw thrown;
            }
            TickContext.resetBrackets();
            Demotions.fluidThrew(fluid, serverLevel, thrown);
        } finally {
            local.ticking = outer;
        }
    }
}
