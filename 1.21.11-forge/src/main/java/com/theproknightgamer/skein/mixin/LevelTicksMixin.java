package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.core.TickMetrics;
import com.theproknightgamer.skein.core.SkeinLevel;
import com.theproknightgamer.skein.core.PhaseGovernor;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.ClusterPartition;
import com.theproknightgamer.skein.core.DeferredBuffer;
import com.theproknightgamer.skein.core.DeferredWork;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.ScheduledTickScope;
import com.theproknightgamer.skein.core.ScheduledTickTicker;
import com.theproknightgamer.skein.core.SkeinTickContainer;
import com.theproknightgamer.skein.core.SubTickOrder;
import com.theproknightgamer.skein.core.TickContext;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Queue;
import java.util.function.BiConsumer;

/**
 * Scheduled block and fluid ticks: the schedule call, the run loop, and the one
 * question a running tick can ask about the run loop.
 *
 * <p>Scheduling is level-wide -- one map of per-chunk queues plus a shared
 * "next tick" heap, all plain collections -- so during any parallel pass the
 * call waits for the barrier. That costs nothing in game terms and, importantly,
 * nothing in ordering either: a {@code ScheduledTick} carries the sub-tick
 * number it was created with, and the per-chunk queue is a priority queue over
 * exactly that. When the tick is inserted has no bearing on when it runs.
 *
 * <p>Running is handed to {@link ScheduledTickTicker}, which splits by
 * connected component rather than by grid so that nothing which could interact
 * is ever reordered. If it declines -- too few ticks, only one component, or a
 * tick whose reach cannot be bounded -- vanilla's own loop runs untouched.
 *
 * <p>Everything here acts only while a pass is open or the scheduled-tick
 * phase is running, so a missing hook costs the in-level phases at most.
 */
@HookFor(Hooks.Feature.IN_LEVEL)
@SuppressWarnings({"rawtypes", "unchecked"})
@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin implements SkeinTickContainer {

    @Shadow @Final private Long2ObjectMap allContainers;

    @Shadow @Final private Queue toRunThisTick;

    @Shadow @Final private List alreadyRunThisTick;

    @Unique
    private ServerLevel skein$level;

    @Unique
    private final ClusterPartition<ScheduledTick<?>> skein$partition = new ClusterPartition<>();

    /**
     * Dropped-tick reports so far. A chunk unloading under a pending schedule is
     * a legitimate, if rare, vanilla event, so this is a symptom to surface
     * rather than an assertion to fail -- and it must not be able to fill a log
     * if it starts happening every tick.
     */
    @Unique
    private final java.util.concurrent.atomic.AtomicLong skein$droppedTicks =
            new java.util.concurrent.atomic.AtomicLong();

    @Override
    public void skein$bindLevel(ServerLevel level) {
        this.skein$level = level;
    }

    @Override
    public ServerLevel skein$level() {
        return this.skein$level;
    }

    @Override
    public ClusterPartition<ScheduledTick<?>> skein$partition() {
        return this.skein$partition;
    }

    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void skein$deferSchedule(ScheduledTick tick, CallbackInfo ci) {
        if (!TickContext.deferring()) {
            return;
        }
        Level level = TickContext.current();
        if (level == null) {
            return;
        }
        LevelTicks self = (LevelTicks) (Object) this;
        // Replayed on the coordinator, where deferring() is false and this
        // injection falls straight through to the vanilla body. The tick gets
        // its sub-tick number there, from the level it belongs to.
        Level owner = this.skein$level != null ? this.skein$level : level;
        if (DeferredWork.deferSchedule(self, tick, SubTickOrder.schedule(self, tick, owner))) {
            ci.cancel();
        }
    }

    /**
     * A tick parked on this thread is already scheduled as far as the thread
     * can tell. Several blocks act on the answer -- a target block hit twice
     * in one tick keeps the first hit's power only because the second sees the
     * first one's tick, and observers and pointed dripstone branch on it -- so
     * it has to be the one vanilla gives.
     */
    @WrapMethod(method = "hasScheduledTick")
    private boolean skein$hasScheduledTick(BlockPos pos, Object type, Operation<Boolean> original) {
        if (TickContext.deferring()) {
            DeferredBuffer buffer = TickContext.buffer();
            if (buffer != null && buffer.hasParkedTick(this, pos, type)) {
                return true;
            }
        }
        return original.call(pos, type);
    }

    /**
     * Timed both ways like every other phase, so that a level whose ticks do
     * not pay for a barrier runs them vanilla's way. Either way the result is
     * the same, tick for tick; only the time differs. Only ticks big enough
     * to go parallel are timed at all, so a level's handful of quiet ticks does
     * not decide for the busy ones.
     */
    @HookFor(Hooks.Feature.SCHEDULED_TICKS)
    @WrapMethod(method = "runCollectedTicks")
    private void skein$runCollectedTicks(BiConsumer ticker, Operation<Void> original) {
        ServerLevel level = this.skein$level;
        if (level == null || this.toRunThisTick.size() < SkeinConfig.Live.minScheduledTicks) {
            original.call(ticker);
            return;
        }
        PhaseGovernor governor = ((SkeinLevel) level).skein$governor();
        boolean parallel = governor.begin(TickMetrics.Phase.SCHEDULED_TICKS, ScheduledTickTicker.eligible(level));
        try {
            if (!parallel || !ScheduledTickTicker.run(this, level, this.toRunThisTick,
                    this.alreadyRunThisTick, ticker, this.skein$partition)) {
                original.call(ticker);
            }
        } finally {
            governor.finish(TickMetrics.Phase.SCHEDULED_TICKS);
        }
    }

    @HookFor(Hooks.Feature.SCHEDULED_TICKS)
    @WrapMethod(method = "willTickThisTick")
    private boolean skein$willTickThisTick(BlockPos pos, Object type, Operation<Boolean> original) {
        ScheduledTickScope scope = TickContext.scheduledScope();
        if (scope != null && scope.owns(this)) {
            return scope.willTick(pos, type);
        }
        return original.call(pos, type);
    }

    /**
     * Serialises the per-chunk tick containers, and says something when one is
     * missing.
     *
     * <p>{@code allContainers} is a plain {@code Long2ObjectOpenHashMap} keyed
     * by chunk, written by {@code addContainer} as a chunk loads and read by
     * {@code schedule} for every block or fluid tick anyone books. In vanilla
     * both happen on the server thread. Under Skein a worker that misses the
     * chunk cache loads the chunk itself, so two workers in one level can be
     * inside {@code put} at the same time, and an open-addressed map that
     * rehashes under a concurrent reader hands back null.
     *
     * <p>What vanilla does with that null is the reason this matters:
     * {@code schedule} drops the tick and reports it through
     * {@code Util.pauseInIde}, which does nothing at all outside a debugger. A
     * fluid whose scheduled tick is dropped never re-evaluates and never
     * drains -- it simply stays where it is, which is exactly what "the water
     * floats and never updates" looks like, with not one line in the log.
     *
     * <p>So the map operations are serialised on the map itself -- each critical
     * section is a single call, so there is no lock to order against anything
     * else -- and a lookup that still comes back empty is logged loudly rather
     * than silently discarded.
     */
    @WrapOperation(
            method = "addContainer",
            at = @At(value = "INVOKE",
                     target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;"
                            + "put(JLjava/lang/Object;)Ljava/lang/Object;"))
    private Object skein$putContainer(Long2ObjectMap map, long key, Object container, Operation<Object> original) {
        synchronized (map) {
            return original.call(map, key, container);
        }
    }

    @WrapOperation(
            method = "removeContainer",
            at = @At(value = "INVOKE",
                     target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;"
                            + "remove(J)Ljava/lang/Object;"))
    private Object skein$removeContainer(Long2ObjectMap map, long key, Operation<Object> original) {
        synchronized (map) {
            return original.call(map, key);
        }
    }

    @WrapOperation(
            method = "schedule",
            at = @At(value = "INVOKE",
                     target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;"
                            + "get(J)Ljava/lang/Object;"))
    private Object skein$lookupContainer(Long2ObjectMap map, long key, Operation<Object> original) {
        Object container;
        synchronized (map) {
            container = original.call(map, key);
        }
        if (container == null) {
            // Vanilla's own report for this is a no-op outside a debugger, and
            // the consequence -- a fluid or a redstone component that stops
            // updating for good -- is invisible until someone notices it.
            long seen = this.skein$droppedTicks.incrementAndGet();
            if (seen <= 8 || Long.bitCount(seen) == 1) {
                Skein.LOGGER.error("Skein: a scheduled tick was dropped for chunk {}, {} -- nothing will "
                                + "update at that position again ({} so far). If the chunk was not "
                                + "unloading, this is the mod's fault; please report it.",
                        ChunkPos.getX(key), ChunkPos.getZ(key), seen);
            }
        }
        return container;
    }

    @WrapOperation(
            method = "sortContainersToTick",
            at = @At(value = "INVOKE",
                     target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;"
                            + "get(J)Ljava/lang/Object;"))
    private Object skein$lookupContainerForTick(Long2ObjectMap map, long key, Operation<Object> original) {
        synchronized (map) {
            return original.call(map, key);
        }
    }
}
