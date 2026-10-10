package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.SkeinConfig;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Explosion;
import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.core.DimensionTicker;
import com.theproknightgamer.skein.core.ExplosionRays;
import com.theproknightgamer.skein.core.CarpetCompat;
import com.theproknightgamer.skein.core.BulkRandom;
import com.theproknightgamer.skein.core.SkeinRandom;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/** Keeps the vanilla rays, resistance callbacks, damage and knockback intact. */
@HookFor(Hooks.Feature.EXPLOSIONS)
@Mixin(Explosion.class)
public abstract class ExplosionMixin implements ExplosionRays.Exposed {
    @Shadow @Final private Level level;
    @Shadow @Final private double x;
    @Shadow @Final private double y;
    @Shadow @Final private double z;
    @Shadow @Final private float radius;
    @Shadow @Final private ExplosionDamageCalculator damageCalculator;
    @Shadow @Final @Nullable private Entity source;

    /** This explosion's ray hits, cast across the pool, waiting to go into its affected blocks. */
    @Unique @Nullable private ExplosionRays.Hits skein$rays;

    /** Exposure of the entities in range, while this explosion is working out knockback. */
    @Unique @Nullable private ExplosionRays.Exposure skein$exposure;

    @Override
    public ExplosionRays.Exposure skein$exposure() {
        return this.skein$exposure;
    }

    /**
     * Once the entity list is final -- after the detonate event has had its
     * say -- works out exposure for the non-living entities in it on the pool;
     * see {@link ExplosionRays.Exposure}.
     */
    @Inject(method = "explode", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/neoforged/neoforge/event/EventHooks;onExplosionDetonate(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/Explosion;Ljava/util/List;D)V",
            remap = false))
    private void skein$exposeInParallel(CallbackInfo ci, @Local List<Entity> nearby) {
        this.skein$exposure = SkeinConfig.Live.explosionHooks && SkeinConfig.Live.parallelExplosionRays && !this.level.isClientSide
                && Skein.pool() != null && Skein.pool().size() > 1 && !DimensionTicker.isQuarantined(this.level)
                ? ExplosionRays.exposure((Explosion) (Object) this, this.level, new Vec3(this.x, this.y, this.z), nearby)
                : null;
    }

    @WrapOperation(method = "explode", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Explosion;getSeenPercent(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/Entity;)F"))
    private float skein$exposedKnockback(Vec3 centre, Entity entity, Operation<Float> original) {
        ExplosionRays.Exposure exposure = this.skein$exposure;
        float seen = exposure == null ? Float.NaN : exposure.lookup(this.level, centre, entity);
        return Float.isNaN(seen) ? original.call(centre, entity) : seen;
    }

    @Inject(method = "explode", at = @At("RETURN"))
    private void skein$forgetExposure(CallbackInfo ci) {
        this.skein$exposure = null;
    }

    @Unique
    private long[] skein$visited;
    @Unique private int skein$minX;
    @Unique private int skein$minY;
    @Unique private int skein$minZ;

    /**
     * The chunks this explosion's rays have read, at most four: a blast of
     * ordinary size spans a 2x2 patch of chunks at most, and larger ones fall
     * back to the level for the rest.
     */
    @Unique private final long[] skein$heldPositions = new long[4];
    @Unique private final LevelChunk[] skein$heldChunks = new LevelChunk[4];
    @Unique private int skein$held;

    @Inject(method = "explode", at = @At("HEAD"))
    private void skein$beginVisited(CallbackInfo ci) {
        this.skein$visited = null;
        this.skein$held = 0;
        this.skein$rays = null;
        this.skein$decided = false;
        this.skein$minX = (int) Math.floor(this.x) - 8;
        this.skein$minY = (int) Math.floor(this.y) - 8;
        this.skein$minZ = (int) Math.floor(this.z) - 8;
    }

    /** Whether this explosion's ray loop has been decided: cast on the pool, or left to run. */
    @Unique private boolean skein$decided;

    /**
     * The ray loop's bound, read before its first ray: the rays are cast on
     * the pool here -- after the explode game event, before the first draw
     * vanilla would take -- and the loop then runs no iterations; see
     * {@link ExplosionRays}.
     *
     * <p>A {@code @ModifyExpressionValue} rather than a {@code @ModifyConstant},
     * because a constant can be taken over by only one mod and whichever has
     * the higher priority breaks the other; this chains, and sees the bound as
     * any other mod left it. A bound that is no longer vanilla's 16 means
     * another mod owns the loop, so nothing is cast and the loop is theirs.
     * Optional for the same reason: without it the loop is simply vanilla's.
     */
    @HookFor(value = {}, yieldsUntil = "skein$feedCastRays", yields = Hooks.Feature.EXPLOSION_RAYS)
    @ModifyExpressionValue(method = "explode", at = @At(value = "CONSTANT", args = "intValue=16", ordinal = 1))
    private int skein$castRaysOnPool(int rays) {
        if (!this.skein$decided) {
            // The bound is read once per outer iteration; decide on the first.
            this.skein$decided = true;
            this.skein$rays = rays == 16 && SkeinConfig.Live.explosionHooks
                    && ExplosionRays.eligible(this.level, this.damageCalculator, this.source)
                    && !CarpetCompat.explosionNoBlockDamage()
                    ? ExplosionRays.cast((Explosion) (Object) this, this.level, this.damageCalculator,
                            this.x, this.y, this.z, this.radius)
                    : null;
        }
        return this.skein$rays != null ? 0 : rays;
    }

    /**
     * The pool's hits go straight into the affected blocks, each block's first
     * hit in ray order: what the set below hands over. The set itself is empty,
     * since vanilla's loop ran no iterations.
     */
    @WrapOperation(method = "explode", at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/objects/ObjectArrayList;addAll(Ljava/util/Collection;)Z", remap = false))
    private boolean skein$feedCastRays(ObjectArrayList<BlockPos> toBlow, Collection<BlockPos> set,
                                       Operation<Boolean> original) {
        ExplosionRays.Hits rays = this.skein$rays;
        if (rays != null) {
            this.skein$rays = null;
            rays.addTo(toBlow);
        }
        return original.call(toBlow, set);
    }

    /**
     * Keeps vanilla's callbacks and improves deduplication. The set keeps
     * first-insertion order, so the affected blocks come out in the order the
     * rays first reached them -- the order the pool's hits can be appended in
     * without a set. Vanilla's {@code HashSet} iterates in hash order, and
     * {@code finalizeExplosion} shuffles either one before anything reads it.
     */
    @ModifyVariable(method = "explode", at = @At("STORE"), ordinal = 0)
    private Set<BlockPos> skein$blockSet(Set<BlockPos> original) {
        return SkeinConfig.Live.explosionHooks && !this.level.isClientSide
                ? new ObjectLinkedOpenHashSet<>() : original;
    }

    @WrapOperation(method = "explode", at = @At(value = "INVOKE", target = "Ljava/util/Set;add(Ljava/lang/Object;)Z"))
    private boolean skein$skipDuplicateBlock(Set<BlockPos> blocks, Object value, Operation<Boolean> original) {
        if (!SkeinConfig.Live.explosionHooks || this.level.isClientSide) return original.call(blocks, value);
        BlockPos pos = (BlockPos) value;
        long dx = (long) pos.getX() - this.skein$minX;
        long dy = (long) pos.getY() - this.skein$minY;
        long dz = (long) pos.getZ() - this.skein$minZ;
        if ((dx | dy | dz) >>> 4 != 0) return original.call(blocks, value);
        // A 16-cube covers ordinary TNT rays. Larger blasts fall back outside
        // it. Only successful insertions are remembered, after all resistance
        // and shouldBlockExplode callbacks have already run. The original set
        // still receives first insertions in exactly the same order.
        int index = (int) (dx | dy << 4 | dz << 8);
        if (this.skein$visited == null) this.skein$visited = new long[64];
        long bit = 1L << index;
        int word = index >>> 6;
        if ((this.skein$visited[word] & bit) != 0) return false;
        boolean added = original.call(blocks, value);
        if (added) this.skein$visited[word] |= bit;
        return added;
    }

    /**
     * A ray step reads its block from the chunk the explosion already holds.
     *
     * <p>{@code Level.getBlockState} finds the chunk and asks it for the state.
     * Finding it is the expensive half -- the chunk source, Skein's per-thread
     * cache and whatever wraps {@code getChunk}, for every one of some thirty
     * thousand ray steps -- and measured on a column of 202 fuse-1 TNT it was
     * nearly a third of each explosion. The first read of each chunk still goes
     * through {@code getChunk} exactly as vanilla's would, with its loading and
     * its ticket; later reads of the same chunk skip straight to what vanilla
     * would have been handed. A held chunk is the chunk object itself, not a
     * copy of its blocks, so {@code finalizeExplosion}, which reads each
     * affected block as the blocks before it break, holds chunks the same way.
     *
     * <p>Carpet redirects this same call to answer bedrock while its
     * {@code explosionNoBlockDamage} rule is on, and this wrapper sits around
     * that redirect, so skipping {@code original} would skip carpet too and
     * blow up blocks the rule protects. While the rule is on, every read goes
     * through.
     */
    @WrapOperation(method = {"explode", "finalizeExplosion"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState skein$blockFromHeldChunk(Level level, BlockPos pos, Operation<BlockState> original) {
        if (!SkeinConfig.Live.explosionHooks || level.isClientSide || level.isDebug() || level.isOutsideBuildHeight(pos)
                || CarpetCompat.explosionNoBlockDamage()) {
            return original.call(level, pos);
        }
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        long position = ChunkPos.asLong(chunkX, chunkZ);
        for (int i = 0; i < this.skein$held; i++) {
            if (this.skein$heldPositions[i] == position) {
                return this.skein$heldChunks[i].getBlockState(pos);
            }
        }
        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        if (this.skein$held < this.skein$heldChunks.length) {
            this.skein$heldPositions[this.skein$held] = position;
            this.skein$heldChunks[this.skein$held++] = chunk;
        }
        return chunk.getBlockState(pos);
    }

    @Inject(method = "finalizeExplosion", at = @At("HEAD"))
    private void skein$beginFinalize(CallbackInfo ci) {
        this.skein$held = 0;
    }

    /**
     * Shuffles the affected blocks with the source every draw would pick: a
     * blast's fourteen hundred draws each asked the thread context which
     * source that was, and the answer cannot change inside the shuffle. The
     * draws themselves are then taken in bulk; see {@link BulkRandom}.
     */
    @WrapOperation(method = "finalizeExplosion", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/Util;shuffle(Ljava/util/List;Lnet/minecraft/util/RandomSource;)V"))
    private void skein$shuffleFromOneSource(List<?> blocks, RandomSource random, Operation<Void> original) {
        if (!SkeinConfig.Live.explosionHooks || this.level.isClientSide) {
            original.call(blocks, random);
            return;
        }
        RandomSource source = random instanceof SkeinRandom skein ? skein.current() : random;
        if (!BulkRandom.confined(this.level) || !BulkRandom.shuffle(blocks, source)) {
            original.call(blocks, source);
        }
    }

    @Inject(method = {"explode", "finalizeExplosion"}, at = @At("RETURN"))
    private void skein$releaseHeldChunks(CallbackInfo ci) {
        // An explosion object can outlive its tick; do not pin chunks with it.
        java.util.Arrays.fill(this.skein$heldChunks, 0, this.skein$held, null);
        this.skein$held = 0;
    }

    @WrapOperation(method = "explode", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"))
    private FluidState skein$fluidFromBlock(Level level, BlockPos pos, Operation<FluidState> original,
                                           @Local BlockState state) {
        // LevelChunkSection.getFluidState reads this same palette entry again.
        // There is no callback or world write between the two reads. Debug
        // worlds synthesize block states, so retain their separate fluid read.
        return SkeinConfig.Live.explosionHooks && !level.isClientSide && !level.isDebug()
                ? state.getFluidState() : original.call(level, pos);
    }
}
