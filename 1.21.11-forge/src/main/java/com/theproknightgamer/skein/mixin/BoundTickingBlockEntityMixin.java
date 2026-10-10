package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.Demotions;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.SkeinRefusal;
import com.theproknightgamer.skein.core.SkeinTicker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Exposes the block entity behind vanilla's ticker wrapper, so the
 * block-entity split can ask what type it is without building a registry-id
 * string for every ticker every tick. See {@link SkeinTicker}.
 *
 * <p>Also keeps a {@link SkeinRefusal} out of vanilla's block-entity crash
 * handler, as {@link TickGuardMixin} does for entities: it would otherwise
 * remove the block entity under Forge's {@code removeErroringBlockEntities}.
 *
 * <p>A failure inside a parallel pass demotes the type. Priority 100 for the
 * reason given on {@link TickGuardMixin}.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity", priority = 100)
public abstract class BoundTickingBlockEntityMixin implements SkeinTicker {

    @Shadow @Final private BlockEntity blockEntity;

    @Override
    public BlockEntity skein$blockEntity() {
        return this.blockEntity;
    }

    @HookFor(Hooks.Feature.IN_LEVEL)
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/BlockEntityTicker;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)V"))
    private <T extends BlockEntity> void skein$survivesRefusal(BlockEntityTicker<T> ticker, Level level, BlockPos pos,
                                                               BlockState state, T blockEntity,
                                                               Operation<Void> original) {
        try {
            original.call(ticker, level, pos, state, blockEntity);
        } catch (RuntimeException | Error thrown) {
            if (!Demotions.absorb(blockEntity, level, thrown)) {
                throw thrown;
            }
        }
    }
}
