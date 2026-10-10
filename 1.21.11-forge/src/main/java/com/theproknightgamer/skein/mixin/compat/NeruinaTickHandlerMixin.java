package com.theproknightgamer.skein.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Neruina keeps one {@code TickHandler} for the whole server and calls its
 * {@code tick} from every level's tick: a counter, and every 600 calls the
 * oldest entry off a plain {@code ArrayList} of recent errors. Two dimensions
 * ticking at once race on it; the loser's {@code removeFirst} finds the list
 * empty, and the dimension it was ticking is dropped back to serial ticking.
 * The error bookkeeping beside it -- a {@code HashMap} of suspended entries and
 * a map of broken block states -- is written from whichever level's tick
 * failed. Each of those writes now holds the handler's monitor.
 *
 * <p>Inside a level, Skein's own guards sit nearer the tick than Neruina's
 * (see {@code TickGuardMixin}), so what fails in a parallel pass is demoted
 * rather than suspended, and Neruina handles a type's failures once it runs on
 * the level thread, as it does without Skein.
 */
@HookFor(Hooks.Feature.DIMENSIONS)
@Pseudo
@Mixin(targets = "com.bawnorton.neruina.handler.TickHandler", remap = false)
public abstract class NeruinaTickHandlerMixin {

    @WrapMethod(method = "tick()V")
    private void skein$tick(Operation<Void> original) {
        synchronized (this) {
            original.call();
        }
    }

    @WrapMethod(method = "init()V")
    private void skein$init(Operation<Void> original) {
        synchronized (this) {
            original.call();
        }
    }

    @WrapMethod(method = "clearTracked()I")
    private int skein$clearTracked(Operation<Integer> original) {
        synchronized (this) {
            return original.call();
        }
    }

    @WrapMethod(method = "trackError(Lcom/bawnorton/neruina/extend/Errorable;Lcom/bawnorton/neruina/util/TickingEntry;)V")
    private void skein$trackError(@Coerce Object errorable, @Coerce Object entry, Operation<Void> original) {
        synchronized (this) {
            original.call(errorable, entry);
        }
    }

    @WrapMethod(method = "addErrored(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V")
    private void skein$addErrored(BlockState state, BlockPos pos, Operation<Void> original) {
        synchronized (this) {
            original.call(state, pos);
        }
    }

    @WrapMethod(method = "removeErrored(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V")
    private void skein$removeErroredState(BlockState state, BlockPos pos, Operation<Void> original) {
        synchronized (this) {
            original.call(state, pos);
        }
    }

    @WrapMethod(method = "removeErrored(Ljava/lang/Object;)V")
    private void skein$removeErrored(Object errored, Operation<Void> original) {
        synchronized (this) {
            original.call(errored);
        }
    }

    @WrapMethod(method = "addTickingEntry(Lcom/bawnorton/neruina/util/TickingEntry;)V")
    private void skein$addTickingEntry(@Coerce Object entry, Operation<Void> original) {
        synchronized (this) {
            original.call(entry);
        }
    }

    @WrapMethod(method = "addTickingEntryUnsafe(Lcom/bawnorton/neruina/util/TickingEntry;)V")
    private void skein$addTickingEntryUnsafe(@Coerce Object entry, Operation<Void> original) {
        synchronized (this) {
            original.call(entry);
        }
    }
}
