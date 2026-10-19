package com.theproknightgamer.skein.mixin;

import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.level.portal.TeleportTransition;


import com.theproknightgamer.skein.core.DeferredWork;

import com.theproknightgamer.skein.core.DimensionTicker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.Entity;

import net.minecraft.world.entity.PortalProcessor;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Relative;
import java.util.Set;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.HookFor;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import com.theproknightgamer.skein.core.TickContext;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.HazardWatch;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Shadow;

import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one thing an ordinary entity tick does that leaves the level it belongs
 * to.
 *
 * <p>{@code handlePortal} is where a mob, a minecart or a piece of primed TNT
 * standing in a portal is moved to another dimension, and every step of it runs
 * against the <em>destination</em> level from the source level's thread: the
 * exit search loads chunks there and writes its point-of-interest index, a
 * missing exit portal is built with {@code setBlock} calls into it, the entity
 * is added to its registry, and a ticket is put on the chunk it lands in. The
 * margin bounds none of that, and a {@link com.theproknightgamer.skein.core.DeferredBuffer}
 * cannot hold it either -- a cell's buffer is drained by its own level's
 * coordinator, while the level being written to is still being ticked by
 * somebody else.
 *
 * <p>The first chunk load is worse than the races. Vanilla's {@code getChunk}
 * sees a thread that is not the destination's own, posts the load to that
 * level's queue and blocks on the result -- and while a phase is open nothing
 * drains that queue, so the barrier never closes and the server stops without
 * saying anything.
 *
 * <p>So the whole transaction waits. The dimension barrier is the only point in
 * the tick at which no level is running at all, and a cell buffer is drained by
 * the level's own coordinator with the pass shut; whichever of the two is open,
 * the teleport ends up on the server thread with nothing else ticking, which is
 * where vanilla does it. With no phase open nothing is parked and this is
 * vanilla untouched.
 *
 * <p>What it costs is that the entity finishes the tick in the dimension it
 * came from rather than leaving partway through it. Nothing downstream of the
 * barrier can tell the two apart: the arrival is in the same server tick, and
 * every dimension is still exactly one tick further along when it ends.
 */
// Priority above the default so the injections still apply when another mod has
// merged (@Overwrite) one of the target methods -- the PVP-bot mod's herobot mixins
// do that to Entity, and Mixin refuses to inject into an equal-priority merge.
@Mixin(value = Entity.class, priority = 1500)
public abstract class EntityMixin {

    @Shadow public PortalProcessor portalProcess;

    @Shadow protected abstract void handlePortal();

    /**
     * A teleport made from a level the caller is not ticking, or into one -- a
     * command block's {@code /tp} into the Nether, an ender pearl landing for a
     * thrower in another dimension, a mod's teleporter. It removes the entity
     * from one level and adds it to another (or moves it inside a level another
     * thread is ticking), and while dimensions tick in parallel at least one of
     * those is somebody else's. The whole teleport waits for the dimension
     * barrier; the caller gets the entity back. Every teleport goes through
     * here, {@code teleportTo} included; one started by the entity's own portal
     * is already parked by {@link #skein$deferPortalTravel}.
     */
    @Inject(method = "teleport", at = @At("HEAD"), cancellable = true)
    private void skein$deferForeignTeleport(TeleportTransition transition, CallbackInfoReturnable<Entity> cir) {
        Entity self = (Entity) (Object) this;
        if (DimensionTicker.crossesTickingLevel(self.level(), transition.newLevel())
                && DimensionTicker.deferPastBarrier(() -> self.teleport(transition))) {
            cir.setReturnValue(self);
        }
    }

    /**
     * The same one level up, for {@code teleportTo}: a subclass override --
     * {@code ServerPlayer}'s wakes the player and resets its camera first --
     * would otherwise run its own part on the caller's thread before reaching
     * the park above.
     */
    @HookFor(Hooks.Feature.DIMENSIONS)
    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z",
            at = @At("HEAD"), cancellable = true)
    private void skein$deferForeignTeleportTo(ServerLevel level, double x, double y, double z,
                                              Set<Relative> relative, float yRot, float xRot, boolean setCamera,
                                              CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (DimensionTicker.crossesTickingLevel(self.level(), level) && DimensionTicker.deferPastBarrier(
                () -> self.teleportTo(level, x, y, z, relative, yRot, xRot, setCamera))) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "handlePortal", at = @At("HEAD"), cancellable = true)
    private void skein$deferPortalTravel(CallbackInfo ci) {
        if (this.portalProcess == null) {
            // No portal in progress means the method is just the cooldown
            // counter, which belongs to this entity and reaches nowhere.
            return;
        }
        // Past the dimension barrier first: while that is open a cell buffer
        // is the wrong place, because it is drained with the other levels still
        // running. Neither queue is open by the time the parked call runs, so
        // the replay takes the vanilla path instead of parking itself again.
        Runnable travel = () -> this.handlePortal();
        if (DimensionTicker.deferPastBarrier(travel) || DeferredWork.park(travel)) {
            ci.cancel();
        }
    }


    /**
     * Standing on a block and landing on one, the two block reactions
     * {@code checkInsideBlocks} does not cover. An item resting on redstone
     * ore lights it every tick it moves, and a sculk sensor takes the step as a
     * vibration, so a clustered entity's contact with a block that reacts is
     * parked for the barrier like its {@code entityInside}.
     */
    @HookFor(Hooks.Feature.IN_LEVEL)
    @WrapOperation(method = "applyEffectsFromBlocks(Ljava/util/List;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;stepOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/Entity;)V"))
    private void skein$parkStepOn(Block block, Level level, BlockPos pos, BlockState state, Entity entity,
                                  Operation<Void> original) {
        if (TickContext.clustered() && SkeinConfig.Live.landingHazards.contains(block)) {
            BlockPos immutable = pos.immutable();
            if (DeferredWork.park(() -> skein$replayWatched(block,
                    () -> original.call(block, level, immutable, state, entity)))) {
                return;
            }
        }
        original.call(block, level, pos, state, entity);
    }

    @HookFor(Hooks.Feature.IN_LEVEL)
    @WrapOperation(method = "checkFallDamage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;fallOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;D)V"))
    private void skein$parkFallOn(Block block, Level level, BlockState state, BlockPos pos, Entity entity,
                                  double fallDistance, Operation<Void> original) {
        if (TickContext.clustered() && SkeinConfig.Live.landingHazards.contains(block)) {
            BlockPos immutable = pos.immutable();
            if (DeferredWork.park(() -> skein$replayWatched(block,
                    () -> original.call(block, level, state, immutable, entity, fallDistance)))) {
                return;
            }
        }
        original.call(block, level, state, pos, entity, fallDistance);
    }

    /**
     * The block under a clustered entity that has just landed, when that block
     * reaches its {@code entityInside} through {@code updateEntityMovementAfterFallOn}
     * -- a Create belt taking an item into its inventory. Two items on one belt
     * twenty blocks long are far enough apart to share a cluster pass and both
     * write the belt's inventory, so the call waits for the barrier like
     * {@code entityInside} itself. What the default does to the entity (its
     * vertical motion zeroed) is then applied at the barrier; by the end of the
     * tick the motion is the same.
     */
    @HookFor(Hooks.Feature.IN_LEVEL)
    @WrapOperation(method = "move", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;updateEntityMovementAfterFallOn(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;)V"))
    private void skein$parkAfterFall(Block block, BlockGetter getter, Entity entity, Operation<Void> original) {
        if (TickContext.clustered() && SkeinConfig.Live.afterFallHazards.contains(block)
                && DeferredWork.park(() -> skein$replayWatched(block, () -> original.call(block, getter, entity)))) {
            return;
        }
        original.call(block, getter, entity);
    }

    /** Runs a parked block reaction, noting whether it wrote anything; see {@link HazardWatch}. */
    @Unique
    private static void skein$replayWatched(Block block, Runnable reaction) {
        TickContext.Local local = TickContext.local();
        long before = local.worldWrites;
        reaction.run();
        HazardWatch.observe(block, local.worldWrites != before);
    }
}
