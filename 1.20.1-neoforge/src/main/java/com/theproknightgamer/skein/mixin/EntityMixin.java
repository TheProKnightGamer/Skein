package com.theproknightgamer.skein.mixin;

import java.util.Set;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraftforge.common.util.ITeleporter;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.server.level.ServerLevel;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.TickContext;
import com.theproknightgamer.skein.core.DeferredWork;
import com.theproknightgamer.skein.core.HazardWatch;
import com.theproknightgamer.skein.core.DimensionTicker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Shadow;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one thing an ordinary entity tick does that leaves the level it belongs
 * to.
 *
 * <p>{@code handleNetherPortal} is where a mob, a minecart or a piece of primed TNT
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
@Mixin(Entity.class)
public abstract class EntityMixin {

    @Shadow protected boolean isInsidePortal;

    @Shadow protected abstract void handleNetherPortal();

    /**
     * A dimension change made from a level the caller is not ticking, or into
     * one -- a command block's {@code /tp} into the Nether, a mod's teleporter.
     * It removes the entity from one level and adds it to another, and while
     * dimensions tick in parallel at least one of them is somebody else's. The
     * whole change waits for the dimension barrier. The caller gets the entity
     * back, as it would for a player; a change started by the entity's own
     * portal is already parked by {@link #skein$deferPortalTravel}.
     */
    // 1.20.1: the one-argument vanilla changeDimension delegates to this
    // Forge overload, so hooking it covers both. Forge-added methods keep
    // their names at runtime, hence remap = false.
    @Inject(method = "changeDimension(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraftforge/common/util/ITeleporter;)Lnet/minecraft/world/entity/Entity;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void skein$deferForeignTravel(ServerLevel destination, ITeleporter teleporter,
                                          CallbackInfoReturnable<Entity> cir) {
        Entity self = (Entity) (Object) this;
        if (DimensionTicker.crossesTickingLevel(self.level(), destination)
                && DimensionTicker.deferPastBarrier(() -> self.changeDimension(destination, teleporter))) {
            cir.setReturnValue(self);
        }
    }

    /** The same for a teleport into another level, which does not go through {@code changeDimension}. */
    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z",
            at = @At("HEAD"), cancellable = true)
    private void skein$deferForeignTeleport(ServerLevel level, double x, double y, double z,
                                            Set<RelativeMovement> relative, float yRot, float xRot,
                                            CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (DimensionTicker.crossesTickingLevel(self.level(), level)
                && DimensionTicker.deferPastBarrier(() -> self.teleportTo(level, x, y, z, relative, yRot, xRot))) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "handleNetherPortal", at = @At("HEAD"), cancellable = true)
    private void skein$deferPortalTravel(CallbackInfo ci) {
        if (!this.isInsidePortal) {
            // No portal in progress means the method is just the cooldown
            // counter, which belongs to this entity and reaches nowhere.
            return;
        }
        // Past the dimension barrier first: while that is open a cell buffer
        // is the wrong place, because it is drained with the other levels still
        // running. Neither queue is open by the time the parked call runs, so
        // the replay takes the vanilla path instead of parking itself again.
        Runnable travel = () -> this.handleNetherPortal();
        if (DimensionTicker.deferPastBarrier(travel) || DeferredWork.park(travel)) {
            ci.cancel();
        }
    }

    /**
     * Parks what a block does to an entity standing in it, when that block
     * writes and the entity is in the cluster tier.
     *
     * <p>{@code checkInsideBlocks} hands every block the entity's box touches a
     * chance to act on it, and the reach then belongs to the <em>block</em>, not
     * to the entity: a wooden pressure plate sets a block, updates its
     * neighbours and schedules a tick, and its sensitivity is EVERYTHING, so a
     * dropped item does it. The grid keeps two concurrent entities two margins
     * apart so those writes land in different chunks; the cluster tier keeps
     * them apart only by what they can reach each other with, which for a
     * projectile is nothing at all. Two items on plates four blocks apart are
     * the same level and the same instant, and they meet inside
     * {@code PalettedContainer} -- or, in different sections of one chunk, in
     * the shared heightmap where nothing checks.
     *
     * <p>So the call is parked and replayed by the coordinator at the barrier,
     * in task order, with nothing else in the level. Same tick, deterministic
     * order, one pass of latency -- the same trade as the navigation sweep.
     * Only blocks that actually write are parked, which is why a bubble column
     * (which writes nothing; it only pushes the entity) still acts in-tick and
     * the farm this tier exists for is unaffected.
     */
    @HookFor(Hooks.Feature.IN_LEVEL)
    @WrapOperation(
            method = "checkInsideBlocks",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/world/level/block/state/BlockState;"
                            + "entityInside(Lnet/minecraft/world/level/Level;"
                            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)V"))
    private void skein$parkEntityInside(BlockState state, Level level, BlockPos pos, Entity entity,
                                        Operation<Void> original) {
        if (TickContext.clustered() && SkeinConfig.Live.hazardBlocks.contains(state.getBlock())) {
            // checkInsideBlocks reuses one MutableBlockPos for the whole sweep.
            BlockPos immutable = pos.immutable();
            if (DeferredWork.park(() -> {
                // Replayed by the coordinator with nothing else running, which
                // is the one place a block's writes can be counted exactly.
                TickContext.Local local = TickContext.local();
                long before = local.worldWrites;
                original.call(state, level, immutable, entity);
                HazardWatch.observe(state.getBlock(), local.worldWrites != before);
            })) {
                return;
            }
        }
        original.call(state, level, pos, entity);
    }

    /**
     * Standing on a block and landing on one, the two block reactions
     * {@code checkInsideBlocks} does not cover. An item resting on redstone
     * ore lights it every tick it moves, and a sculk sensor takes the step as a
     * vibration, so a clustered entity's contact with a block that reacts is
     * parked for the barrier like its {@code entityInside}.
     */
    @HookFor(Hooks.Feature.IN_LEVEL)
    @WrapOperation(method = "move", at = @At(value = "INVOKE",
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
            target = "Lnet/minecraft/world/level/block/Block;fallOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;F)V"))
    private void skein$parkFallOn(Block block, Level level, BlockState state, BlockPos pos, Entity entity,
                                  float fallDistance, Operation<Void> original) {
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
     * reaches its {@code entityInside} through {@code updateEntityAfterFallOn}
     * -- a Create belt taking an item into its inventory. Two items on one belt
     * twenty blocks long are far enough apart to share a cluster pass and both
     * write the belt's inventory, so the call waits for the barrier like
     * {@code entityInside} itself. What the default does to the entity (its
     * vertical motion zeroed) is then applied at the barrier; by the end of the
     * tick the motion is the same.
     */
    @HookFor(Hooks.Feature.IN_LEVEL)
    @WrapOperation(method = "move", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;updateEntityAfterFallOn(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;)V"))
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
