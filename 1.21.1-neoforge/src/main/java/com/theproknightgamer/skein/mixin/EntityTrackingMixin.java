package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.DimensionTicker;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.TrackingPass;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entity tracking asks the same question thousands of times a tick.
 *
 * <p>{@code ChunkMap.tick} walks every tracked entity and, for each one that
 * has not changed section, asks the distance manager whether its chunk is in
 * entity-ticking range before sending its movement -- a probe into a hash map
 * of every chunk in simulation distance, usually a cache miss. Measured on a
 * world with a render distance of 32 and a 1,880-snowball column, that probe
 * alone was a tenth of the server thread.
 *
 * <p>Entities crowd into few chunks, so the answers are kept in a small
 * direct-mapped table for the length of one walk. Nothing in the loop adds or
 * removes tickets -- it sends packets and updates who can see what -- so an
 * answer cannot go stale within a walk, and bumping the generation at the start
 * of the next one forgets them all without clearing anything.
 *
 * <p>The call is made through {@code ChunkMap}'s own package-private
 * {@code DistanceManager} subclass, which is the owner the bytecode names.
 */
@HookFor(Hooks.Feature.ENTITY_TRACKING)
@Mixin(ChunkMap.class)
public abstract class EntityTrackingMixin {

    @Shadow @Final ServerLevel level;

    @Shadow public abstract DistanceManager getDistanceManager();

    @Unique private final TrackingPass.Walk skein$walk = new TrackingPass.Walk();

    /**
     * The first loop over the tracked entities walks only the ones with
     * something left to do there, once the pool has done the rest; see
     * {@link TrackingPass}. The second loop, for players that changed section,
     * still visits every entity.
     */
    @HookFor(value = Hooks.Feature.ENTITY_TRACKING, yieldsUntil = "skein$sendCollected")
    @WrapOperation(method = "tick()V", at = @At(value = "INVOKE", ordinal = 0, remap = false,
            target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;values()Lit/unimi/dsi/fastutil/objects/ObjectCollection;"))
    private ObjectCollection<?> skein$walkOnPool(Int2ObjectMap<?> tracked, Operation<ObjectCollection<?>> original) {
        ObjectCollection<?> values = original.call(tracked);
        if (!TrackingPass.eligible() || tracked.size() < TrackingPass.minEntities
                || DimensionTicker.isQuarantined(this.level)) {
            return values;
        }
        return this.skein$walk.prepare(this.level, this.getDistanceManager(), values, tracked.size());
    }

    @WrapOperation(method = "tick()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerEntity;sendChanges()V"))
    private void skein$sendCollected(ServerEntity entity, Operation<Void> original) {
        if (!this.skein$walk.sendCollected(entity)) {
            original.call(entity);
        }
    }

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void skein$finishWalk(CallbackInfo ci) {
        this.skein$walk.finish();
    }

    @Unique private static final int SKEIN$SLOTS = 256;

    @Unique private final long[] skein$trackedChunks = new long[SKEIN$SLOTS];
    @Unique private final int[] skein$trackedGeneration = new int[SKEIN$SLOTS];
    @Unique private final boolean[] skein$trackedTicking = new boolean[SKEIN$SLOTS];
    @Unique private int skein$generation;

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void skein$forgetTrackedChunks(CallbackInfo ci) {
        // Zero is what a fresh table holds, so never use it as a live generation.
        if (++this.skein$generation == 0) {
            this.skein$generation = 1;
            java.util.Arrays.fill(this.skein$trackedGeneration, 0);
        }
    }

    @WrapOperation(method = "tick()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap$DistanceManager;inEntityTickingRange(J)Z"))
    private boolean skein$sameChunkSameAnswer(@Coerce DistanceManager manager, long chunk, Operation<Boolean> original) {
        if (!SkeinConfig.Live.trackingHooks) {
            return original.call(manager, chunk);
        }
        int slot = (int) HashCommon.mix(chunk) & (SKEIN$SLOTS - 1);
        if (this.skein$trackedGeneration[slot] == this.skein$generation && this.skein$trackedChunks[slot] == chunk) {
            return this.skein$trackedTicking[slot];
        }
        boolean ticking = original.call(manager, chunk);
        this.skein$trackedChunks[slot] = chunk;
        this.skein$trackedTicking[slot] = ticking;
        this.skein$trackedGeneration[slot] = this.skein$generation;
        return ticking;
    }
}
