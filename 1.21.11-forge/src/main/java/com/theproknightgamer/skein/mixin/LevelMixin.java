package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.DeferredWork;
import com.theproknightgamer.skein.core.ClusterPartition;
import com.theproknightgamer.skein.core.ClusterPass;
import com.theproknightgamer.skein.core.Partition;
import com.theproknightgamer.skein.core.PhaseGovernor;
import com.theproknightgamer.skein.core.SkeinLevel;
import com.theproknightgamer.skein.core.SkeinRandom;
import com.theproknightgamer.skein.core.SubTickOrder;
import com.theproknightgamer.skein.core.TickContext;
import com.theproknightgamer.skein.core.CollisionQueries;
import com.theproknightgamer.skein.core.ProjectileQueries;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraftforge.common.util.BlockSnapshot;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Per-level plumbing, one vanilla guard that has to be taught about workers,
 * the block-entity phase, and the pieces of level state that are
 * single-threaded by construction in vanilla and therefore have to be handed
 * out per thread here.
 *
 * <p>The guard is {@code getBlockEntity}, which returns null outright when the
 * caller is not the thread that constructed the level. It is a real safety
 * check -- block entities are not safe to touch from arbitrary threads -- but
 * it identifies the safe thread by an identity captured at world load, and
 * under Skein the thread that owns a level changes every tick. Left alone it
 * would silently report every chest, hopper and furnace in the game as absent,
 * which is far worse than the race it is guarding against.
 *
 * <p>The per-thread state is the neighbour-update state machine, the random
 * source, the little linear generator behind {@code getBlockRandomPos}, and
 * Forge's block-snapshot capture bracket. The last is the least obvious and
 * the most damaging: {@code captureBlockSnapshots} is a plain field on the
 * level, and while it is set every {@code setBlock} on that level skips
 * {@code markAndNotifyBlock}. One thread opening a capture bracket would
 * silently stop every other thread's block changes from reaching clients or
 * triggering physics, so the bracket is made per thread while a pass is open.
 */
@Mixin(Level.class)
public abstract class LevelMixin implements SkeinLevel {

    @HookFor({Hooks.Feature.PROJECTILE_QUERIES, Hooks.Feature.COLLISION_QUERIES})
    @WrapOperation(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/LevelEntityGetter;get(Lnet/minecraft/world/phys/AABB;Ljava/util/function/Consumer;)V"))
    private void skein$projectileCandidates(LevelEntityGetter<Entity> getter, AABB bounds, Consumer<Entity> consumer,
            Operation<Void> original, @Local(argsOnly = true) Predicate<? super Entity> predicate) {
        if (predicate instanceof ProjectileQueries.Filter filter && filter.level() == (Object) this) {
            consumer = new ProjectileQueries.Candidates<>(consumer);
        } else if (predicate instanceof CollisionQueries.Filter) {
            consumer = new CollisionQueries.Candidates<>(consumer);
        }
        original.call(getter, bounds, consumer);
    }

    @Shadow @Final protected CollectingNeighborUpdater neighborUpdater;

    @Shadow @Final @Mutable public RandomSource random;

    @Unique
    private Partition<Entity> skein$entityPartition;

    @Unique
    private Partition<LevelChunk> skein$chunkPartition;

    @Unique
    private Partition<TickingBlockEntity> skein$blockEntityPartition;

    @Unique
    private ClusterPartition<TickingBlockEntity> skein$blockEntityGroups;

    @Unique
    private ClusterPass skein$clusterPass;

    @Unique
    private PhaseGovernor skein$governor;

    @Unique
    private ThreadLocal<CollectingNeighborUpdater> skein$neighborUpdaters;

    @Unique
    private ReentrantLock skein$chunkLoadLock;

    @Unique
    private AtomicLong skein$chunkGeneration;

    /**
     * The sub-tick counter, made atomic.
     *
     * <p>Every scheduled tick is stamped with {@code subTickCount++} when it is
     * created, and that number is the entire ordering guarantee the tick system
     * offers -- ticks run in (priority, sub-tick) order, which is why redstone
     * is deterministic. It is a plain field, so two threads scheduling at once
     * lose an increment and produce two ticks that claim the same place in the
     * sequence.
     *
     * <p>Inside a pass no number is drawn at all: the tick is parked with its
     * schedule and numbered when the coordinator applies it, in vanilla's order
     * ({@link SubTickOrder}). The counter is still atomic for whatever draws
     * outside a pass from another thread -- a mod scheduling into this level
     * from its own.
     */
    @Unique
    private final AtomicLong skein$subTickCount = new AtomicLong();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void skein$init(WritableLevelData levelData, ResourceKey<Level> dimension,
                            RegistryAccess registries, Holder<DimensionType> dimensionType,
                            boolean isClientSide, boolean isDebug, long biomeZoomSeed,
                            int maxChainedNeighborUpdates, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        this.skein$entityPartition = new Partition<>();
        this.skein$chunkPartition = new Partition<>();
        this.skein$blockEntityPartition = new Partition<>();
        this.skein$blockEntityGroups = new ClusterPartition<>();
        this.skein$clusterPass = new ClusterPass();
        this.skein$governor = new PhaseGovernor();
        this.skein$chunkLoadLock = new ReentrantLock();
        this.skein$chunkGeneration = new AtomicLong();
        this.skein$neighborUpdaters = ThreadLocal.withInitial(
                () -> new CollectingNeighborUpdater(self, maxChainedNeighborUpdates));

        // Not gated on any phase being on right now: the wrapper has to exist
        // before the first level is built, and a phase can be switched on by a
        // config reload long after that. It costs a branch per draw and nothing
        // else while no pass is open.
        if (!isClientSide && SkeinConfig.Live.enabled && SkeinConfig.Live.threadSafeRandom) {
            this.random = new SkeinRandom(this.random);
        }
    }

    @Override
    public Partition<Entity> skein$entityPartition() {
        return this.skein$entityPartition;
    }

    @Override
    public Partition<LevelChunk> skein$chunkPartition() {
        return this.skein$chunkPartition;
    }

    @Override
    public Partition<TickingBlockEntity> skein$blockEntityPartition() {
        return this.skein$blockEntityPartition;
    }

    @Override
    public ClusterPartition<TickingBlockEntity> skein$blockEntityGroups() {
        return this.skein$blockEntityGroups;
    }

    @Override
    public ClusterPass skein$clusterPass() {
        return this.skein$clusterPass;
    }

    @Override
    public PhaseGovernor skein$governor() {
        return this.skein$governor;
    }

    @Override
    public CollectingNeighborUpdater skein$neighborUpdater() {
        return this.skein$neighborUpdaters.get();
    }

    @Override
    public CollectingNeighborUpdater skein$vanillaNeighborUpdater() {
        return this.neighborUpdater;
    }

    @Override
    public ReentrantLock skein$chunkLoadLock() {
        return this.skein$chunkLoadLock;
    }

    @Override
    public long skein$chunkGeneration() {
        return this.skein$chunkGeneration.get();
    }

    @Override
    public void skein$bumpChunkGeneration() {
        this.skein$chunkGeneration.incrementAndGet();
    }

    @Unique
    private final AtomicLong skein$blockEpoch = new AtomicLong();

    @Override
    public long skein$blockEpoch() {
        return this.skein$blockEpoch.get();
    }

    @Override
    public void skein$bumpBlockEpoch() {
        this.skein$blockEpoch.incrementAndGet();
    }

    @HookFor(Hooks.Feature.IN_LEVEL)
    @WrapMethod(method = "nextSubTickCount")
    private long skein$nextSubTickCount(Operation<Long> original) {
        if (TickContext.deferring()) {
            return SubTickOrder.UNSTAMPED;
        }
        return this.skein$subTickCount.getAndIncrement();
    }

    @WrapOperation(
            method = "getBlockEntity",
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
                     target = "Lnet/minecraft/world/level/Level;thread:Ljava/lang/Thread;"))
    private Thread skein$blockEntityOwner(Level self, Operation<Thread> original) {
        return TickContext.owns(self) ? Thread.currentThread() : original.call(self);
    }

    @HookFor(Hooks.Feature.IN_LEVEL)
    @WrapOperation(
            method = "neighborShapeChanged",
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
                     target = "Lnet/minecraft/world/level/Level;neighborUpdater:Lnet/minecraft/world/level/redstone/CollectingNeighborUpdater;"))
    private CollectingNeighborUpdater skein$shapeUpdater(Level self, Operation<CollectingNeighborUpdater> original) {
        return TickContext.deferring() ? this.skein$neighborUpdaters.get() : original.call(self);
    }

    /**
     * {@code getBlockRandomPos} steps a plain int in place and slices the result
     * into a position. Every random tick and every ice-and-snow check goes
     * through it, so during a pass each thread steps its own -- otherwise two
     * threads lose each other's updates and start picking the same block.
     */
    @HookFor(Hooks.Feature.IN_LEVEL)
    @WrapOperation(
            method = "getBlockRandomPos",
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
                     target = "Lnet/minecraft/world/level/Level;randValue:I"))
    private int skein$readRandValue(Level self, Operation<Integer> original) {
        return TickContext.deferring() ? TickContext.local().randValue : original.call(self);
    }

    @HookFor(Hooks.Feature.IN_LEVEL)
    @WrapOperation(
            method = "getBlockRandomPos",
            at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
                     target = "Lnet/minecraft/world/level/Level;randValue:I"))
    private void skein$writeRandValue(Level self, int value, Operation<Void> original) {
        if (TickContext.deferring()) {
            TickContext.local().randValue = value;
        } else {
            original.call(self, value);
        }
    }

    @HookFor(Hooks.Feature.IN_LEVEL)
    @WrapOperation(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
                     target = "Lnet/minecraft/world/level/Level;captureBlockSnapshots:Z"))
    private boolean skein$capturingHere(Level self, Operation<Boolean> original) {
        return TickContext.deferring() ? TickContext.local().capturing : original.call(self);
    }

    @HookFor(Hooks.Feature.IN_LEVEL)
    @WrapOperation(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
                     target = "Lnet/minecraft/world/level/Level;capturedBlockSnapshots:Ljava/util/ArrayList;"))
    private ArrayList<BlockSnapshot> skein$capturedHere(Level self, Operation<ArrayList<BlockSnapshot>> original) {
        return TickContext.deferring() ? TickContext.local().captured : original.call(self);
    }

    @HookFor(Hooks.Feature.IN_LEVEL)
    @Inject(method = "addBlockEntityTicker", at = @At("HEAD"), cancellable = true)
    private void skein$deferTicker(TickingBlockEntity ticker, CallbackInfo ci) {
        if (TickContext.deferring()) {
            Level self = (Level) (Object) this;
            DeferredWork.deferOrdered(self, () -> self.addBlockEntityTicker(ticker));
            ci.cancel();
        }
    }

    @HookFor(Hooks.Feature.IN_LEVEL)
    @Inject(method = "addFreshBlockEntities", at = @At("HEAD"), cancellable = true)
    private void skein$deferFreshBlockEntities(Collection<BlockEntity> blockEntities, CallbackInfo ci) {
        if (TickContext.deferring()) {
            Level self = (Level) (Object) this;
            // Copied: the caller is free to reuse or clear its collection the
            // moment this returns, and the real add happens a pass later.
            Collection<BlockEntity> snapshot = java.util.List.copyOf(blockEntities);
            DeferredWork.defer(self, () -> self.addFreshBlockEntities(snapshot));
            ci.cancel();
        }
    }

    /**
     * Counts world mutations so {@link com.theproknightgamer.skein.core.HazardWatch} can
     * tell a block that merely reacts to an entity from one that writes because
     * of it. One increment on a path that already does far more than that.
     */
    @HookFor({})
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"))
    private void skein$countWorldWrite(BlockPos pos, BlockState state, int flags, int limit,
                                       CallbackInfoReturnable<Boolean> cir) {
        TickContext.local().worldWrites++;
    }
}
