package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.DeferredWork;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.SkeinLevel;
import com.theproknightgamer.skein.core.SkeinTickContainer;
import com.theproknightgamer.skein.core.OrderedGroups;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.redstone.NeighborUpdater;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

/**
 * The structural writes a parallel tick can make that reach outside its own
 * cell. The entity loop itself is taken over by {@link EntityLoopMixin}.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    /**
     * {@code LevelTicks} has no reference to the level it belongs to, and the
     * scheduled-tick phase needs one. Bound here rather than in the constructor
     * because the constructor's argument list is long enough that matching it
     * is a liability across versions, and this costs two field writes a tick.
     */
    @HookFor(Hooks.Feature.SCHEDULED_TICKS)
    @Inject(method = "tick", at = @At("HEAD"))
    private void skein$bindTickContainers(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        ((SkeinTickContainer) self.getBlockTicks()).skein$bindLevel(self);
        ((SkeinTickContainer) self.getFluidTicks()).skein$bindLevel(self);
    }

    /**
     * Spawning touches the level-wide id and uuid maps and the section index,
     * none of which are safe to write from several cells at once, so it waits
     * for the barrier. Callers are told it succeeded; the only observable
     * difference is that the entity does not answer area queries until the
     * pass ends.
     *
     * <p>A spawn into this level from another level's tick -- a mod putting a
     * mob in the Nether from the Overworld -- waits for the dimension barrier
     * instead, since this level may be ticking on another thread.
     */
    @Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
    private void skein$deferSpawn(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (DeferredWork.parkSpawn(self, () -> self.addFreshEntity(entity))) {
            cir.setReturnValue(true);
        }
    }

    /** {@code blockEvents} is a plain linked set drained once per tick. */
    @HookFor(Hooks.Feature.IN_LEVEL)
    @Inject(method = "blockEvent", at = @At("HEAD"), cancellable = true)
    private void skein$deferBlockEvent(BlockPos pos, Block block, int param, int data, CallbackInfo ci) {
        if (TickContext.deferring()) {
            ServerLevel self = (ServerLevel) (Object) this;
            DeferredWork.deferOrdered(self, () -> self.blockEvent(pos.immutable(), block, param, data));
            ci.cancel();
        }
    }

    /**
     * The one thing an entity tick does that the geometry cannot bound.
     *
     * <p>{@code sendBlockUpdated} ends by walking {@code navigatingMobs} -- every
     * mob in the level that is currently pathing -- and recomputing the path of
     * any whose route crosses the changed block. A path is not bounded by the
     * margin, so a mob placing a block in one cell can reach into a mob being
     * ticked in another, mutate its navigation mid-tick, and iterate a set that
     * a third thread's spawn is writing to. It is the reason entity threading
     * used to be off by default.
     *
     * <p>Rather than reimplement the method, Skein replays it. At the barrier
     * {@code deferring()} is false, so the call that lands there runs the whole
     * vanilla body -- client updates, navigation sweep, and any other mod's
     * injections into it -- on the coordinator, with nothing else in the level.
     * (1.20.1 has no level-wide path-type cache to invalidate: each mob's node
     * evaluator keeps its own.)
     *
     * <p>What that costs is one pass of latency on path invalidation: a mob that
     * ticks later in the <em>same</em> pass keeps its stale route for one tick.
     * Mobs in later passes see the recomputed one, because the barrier applies
     * this before they run.
     */
    @HookFor(Hooks.Feature.IN_LEVEL)
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"), cancellable = true)
    private void skein$deferNavigationSweep(BlockPos pos, BlockState oldState, BlockState newState, int flags,
                                            CallbackInfo ci) {
        if (!TickContext.deferring()) {
            return;
        }
        ServerLevel self = (ServerLevel) (Object) this;
        BlockPos immutable = pos.immutable();
        DeferredWork.deferOrdered(self, () -> self.sendBlockUpdated(immutable, oldState, newState, flags));
        ci.cancel();
    }

    /**
     * Parks the vibration dispatch until the barrier.
     *
     * <p>{@code GameEvent.register} gives every event a notification radius of
     * {@link net.minecraft.world.level.gameevent.GameEvent#DEFAULT_NOTIFICATION_RADIUS}
     * -- sixteen blocks -- and {@code post} <em>writes</em> into every vibration
     * listener in that cube: a sculk sensor changes state and schedules a tick.
     * So the true reach of any entity that emits a game event is 16, not the
     * two or three blocks its own movement suggests, and
     * {@code PROJECTILE_LAND} fires on every projectile that dies.
     *
     * <p>That radius is what makes a dense projectile farm a single lump: at
     * reach 16 every entity in it conflicts with every other, and no split --
     * by cell or by conflict graph -- can separate them. Deferring the dispatch
     * drops the audited reach of a plain projectile to about four blocks, which
     * is what makes {@link com.theproknightgamer.skein.core.EntityLevels} able to find any
     * concurrency inside a cell at all.
     *
     * <p>The event still fires, in the same tick, to the same listeners, in
     * deterministic task order. Only the thread and the position within the
     * tick move -- the same trade already made for the navigation sweep above.
     * A sculk sensor observes the vibration at the barrier rather than mid-pass.
     *
     * <p>Except where order is exact ({@code OrderedGroups}: scheduled ticks and
     * block entities), and a sculk sensor that hears a door before or after its
     * own tick in vanilla must do so here. When every listener the event can
     * reach is within the reach of the thing that fired it, which is where its
     * own writes are, the event is heard on the spot. One that reaches further
     * is parked, and its group finishes in vanilla order on the coordinator.
     */
    @HookFor(Hooks.Feature.IN_LEVEL)
    @Inject(method = "gameEvent", at = @At("HEAD"), cancellable = true)
    private void skein$deferGameEvent(GameEvent event, Vec3 pos, GameEvent.Context context,
                                      CallbackInfo ci) {
        if (!TickContext.deferring()) {
            return;
        }
        ServerLevel self = (ServerLevel) (Object) this;
        TickContext.Local local = TickContext.local();
        if (local.level == self && OrderedGroups.within(local, pos.x, pos.z, event.getNotificationRadius())) {
            return;
        }
        if (DeferredWork.parkFor(self, () -> self.gameEvent(event, pos, context))) {
            ci.cancel();
        }
    }

    /**
     * Every method here names its descriptor: a bare name picks the first
     * overload only, which left {@code neighborChanged}'s second overload --
     * and on 1.21.11 the {@code updateNeighborsAt} that does the work -- on
     * the shared updater.
     */
    @HookFor(Hooks.Feature.IN_LEVEL)
    @WrapOperation(
            method = {
                    "updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V",
                    "updateNeighborsAtExceptFromFacing(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/Direction;)V",
                    "neighborChanged(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;)V",
                    "neighborChanged(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;Z)V"},
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
                     target = "Lnet/minecraft/server/level/ServerLevel;neighborUpdater:Lnet/minecraft/world/level/redstone/NeighborUpdater;"))
    private NeighborUpdater skein$perThreadNeighborUpdater(ServerLevel self, Operation<NeighborUpdater> original) {
        // CollectingNeighborUpdater is a state machine with a shared stack and a
        // recursion counter. Two cells pushing update chains through one
        // instance would interleave them; a per-thread instance keeps each
        // chain whole, at the cost of the chain-length cap being counted per
        // thread rather than per level.
        return TickContext.deferring() ? ((SkeinLevel) self).skein$neighborUpdater() : original.call(self);
    }
}
