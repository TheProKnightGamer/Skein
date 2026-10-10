package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.SkeinLevel;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Unique;
import com.theproknightgamer.skein.core.DeferredWork;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The other half of {@link LevelMixin}'s capture handling.
 *
 * <p>{@code LevelChunk.setBlockState} skips {@code BlockState.onPlace} while
 * the level is capturing block snapshots, on the assumption that the placement
 * may still be rolled back. That assumption belongs to the thread that opened
 * the bracket; every other thread's block change should place normally.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    // Vanilla's setBlockState by its full descriptor here and below: WorldEdit
    // adds a four-argument overload that sets a flag and calls this one, and a
    // bare name leaves Mixin to pick whichever overload comes first.
    @HookFor(Hooks.Feature.IN_LEVEL)
    @WrapOperation(
            method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
                     target = "Lnet/minecraft/world/level/Level;captureBlockSnapshots:Z"))
    private boolean skein$capturingHere(Level level, Operation<Boolean> original) {
        return TickContext.deferring() ? TickContext.local().capturing : original.call(level);
    }

    @Shadow @Final Level level;

    /** A changed block moves the level's block epoch; see {@link SkeinLevel#skein$blockEpoch}. */
    @HookFor(Hooks.Feature.EXPLOSIONS)
    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"))
    private void skein$blockChanged(BlockPos pos, BlockState state, boolean moving, CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() != null) {
            ((SkeinLevel) this.level).skein$bumpBlockEpoch();
        }
    }

    @Shadow
    private <T extends BlockEntity> void addGameEventListener(T blockEntity, ServerLevel level) {
        throw new AssertionError("shadow");
    }

    @Shadow
    private <T extends BlockEntity> void removeGameEventListener(T blockEntity, ServerLevel level) {
        throw new AssertionError("shadow");
    }

    /**
     * A block entity's game-event listener joins and leaves the chunk at the
     * barrier during a pass. Vanilla keeps listeners per section, which one
     * cell owns; Lithium's {@code world.game_events.dispatch} also keeps a
     * per-level map of which sections have any, written as a section gains its
     * first listener or loses its last, and two cells placing or breaking sculk
     * at once corrupt it. The events themselves already wait for the barrier
     * ({@code ServerLevelMixin}), in the same order, so none is heard late.
     */
    @HookFor(Hooks.Feature.IN_LEVEL)
    @Inject(method = "addGameEventListener", at = @At("HEAD"), cancellable = true)
    private <T extends BlockEntity> void skein$parkListenerAdd(T blockEntity, ServerLevel level, CallbackInfo ci) {
        if (TickContext.deferring() && skein$listens(blockEntity, level)
                && DeferredWork.park(() -> this.addGameEventListener(blockEntity, level))) {
            ci.cancel();
        }
    }

    @HookFor(Hooks.Feature.IN_LEVEL)
    @Inject(method = "removeGameEventListener", at = @At("HEAD"), cancellable = true)
    private <T extends BlockEntity> void skein$parkListenerRemove(T blockEntity, ServerLevel level,
                                                                  CallbackInfo ci) {
        if (TickContext.deferring() && skein$listens(blockEntity, level)
                && DeferredWork.park(() -> this.removeGameEventListener(blockEntity, level))) {
            ci.cancel();
        }
    }

    @Unique
    private static boolean skein$listens(BlockEntity blockEntity, ServerLevel level) {
        return blockEntity.getBlockState().getBlock() instanceof EntityBlock block
                && block.getListener(level, blockEntity) != null;
    }
}
