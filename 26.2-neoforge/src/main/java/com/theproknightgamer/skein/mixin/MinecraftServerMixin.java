package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.DimensionTicker;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import com.theproknightgamer.skein.core.TickContext;
import com.theproknightgamer.skein.core.DeferredWork;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Takes over the level loop in {@code tickChildren}.
 *
 * <p>Rather than rewrite the method, this intercepts the single call that feeds
 * its loop. {@link DimensionTicker} ticks the levels itself and the redirect
 * then hands back an empty array, so the vanilla loop runs zero iterations and
 * everything around it -- command functions before, connections and players
 * after -- keeps its exact position in the tick. If Skein declines the work the
 * real array comes back and vanilla proceeds untouched.
 *
 * <p>Both hooks here matter only while dimensions are ticking in parallel.
 */
@HookFor(Hooks.Feature.DIMENSIONS)
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Shadow @Final private Map<ResourceKey<Level>, long[]> perWorldTickTimes;

    @Shadow public abstract int getTickCount();

    @Shadow private ServerLevel[] getWorldArray() {
        throw new AssertionError("shadow");
    }

    @WrapOperation(
            method = "tickChildren",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;getWorldArray()[Lnet/minecraft/server/level/ServerLevel;"))
    private ServerLevel[] skein$tickLevelsInParallel(MinecraftServer self, Operation<ServerLevel[]> original,
                                                     BooleanSupplier haveTime) {
        ServerLevel[] levels = original.call(self);
        boolean handled = DimensionTicker.tickLevels(
                self,
                levels,
                haveTime,
                this::skein$recordTickTime);
        return handled ? DimensionTicker.NO_LEVELS : levels;
    }

    private void skein$recordTickTime(ServerLevel level, long nanos) {
        // Written from worker threads, and perWorldTickTimes is an
        // IdentityHashMap. One uncontended lock per dimension per tick is
        // nothing next to the tick it is measuring.
        synchronized (this.perWorldTickTimes) {
            this.perWorldTickTimes.computeIfAbsent(level.dimension(), key -> new long[100])[this.getTickCount() % 100] = nanos;
        }
    }

    /**
     * While workers own the levels, the server thread must not reach into their
     * chunk systems. Nothing in Skein's own barrier calls this -- the wait loop
     * parks rather than polls -- so this exists to catch third-party code that
     * calls {@code managedBlock} from a level tick and would otherwise pull the
     * server thread into a {@code ChunkMap} a worker is already inside.
     *
     * <p>Only the per-level half is held back, which is why this replaces the
     * loop's source rather than refusing the whole method. The statement above
     * that loop drains the server's own queue, and that queue is the only thing
     * {@code managedBlock} can make progress on: a caller blocked on a future
     * whose continuation was posted there would never see it complete, and would
     * spin until the watchdog killed the server -- which is what {@code /reload}
     * from a command block does, since it blocks the server thread on exactly
     * such a future from inside a level tick.
     *
     * <p>What that queue hands back can still touch a level a worker owns, which
     * is a race -- but one only a thread that is already blocked can reach, and
     * the alternative for that thread is a wait that never ends.
     */
    @WrapOperation(
            method = "pollTaskInternal",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;getAllLevels()Ljava/lang/Iterable;"))
    private Iterable<ServerLevel> skein$holdLevelQueuesDuringPhase(MinecraftServer self,
                                                                   Operation<Iterable<ServerLevel>> original) {
        if (DimensionTicker.phaseActive()) {
            return List.of();
        }
        return original.call(self);
    }

    /**
     * A save asked for from inside a level's tick -- a command block's
     * {@code /save-all} -- while dimensions tick in parallel. It writes every
     * level's chunks, and all but one of those levels are being ticked by
     * other threads, so it waits for the dimension barrier; the command
     * reports success, as it will be.
     */
    @WrapMethod(method = "saveEverything")
    private boolean skein$saveEverythingAfterBarrier(boolean suppressLog, boolean flush, boolean forced,
                                                     Operation<Boolean> original) {
        if (skein$parkSave(() -> original.call(suppressLog, flush, forced))) {
            return true;
        }
        return original.call(suppressLog, flush, forced);
    }

    /** The same for a mod that saves the chunks alone. */
    @WrapMethod(method = "saveAllChunks")
    private boolean skein$saveChunksAfterBarrier(boolean suppressLog, boolean flush, boolean forced,
                                                 Operation<Boolean> original) {
        if (skein$parkSave(() -> original.call(suppressLog, flush, forced))) {
            return true;
        }
        return original.call(suppressLog, flush, forced);
    }

    @Unique
    private static boolean skein$parkSave(Runnable save) {
        if (TickContext.current() == null) {
            return false;
        }
        return DimensionTicker.deferPastBarrier(save) || DeferredWork.park(save);
    }
}
