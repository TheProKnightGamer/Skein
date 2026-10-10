package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.BulkRandom;
import com.theproknightgamer.skein.core.DimensionTicker;
import com.theproknightgamer.skein.core.ExplosionRays;
import com.theproknightgamer.skein.core.CarpetCompat;
import com.theproknightgamer.skein.core.SkeinRandom;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Set;
/**
 * Keeps the vanilla rays, resistance callbacks, damage and knockback intact.
 *
 * <p>1.21.11 splits what 1.21.1 did in {@code Explosion.explode} and
 * {@code finalizeExplosion} across {@code ServerExplosion}:
 * {@code calculateExplodedPositions} casts the rays, {@code hurtEntities}
 * damages and pushes, and {@code interactWithBlocks} breaks. Each piece of
 * this mixin sits in the method that now does its part.
 */
@HookFor(Hooks.Feature.EXPLOSIONS)
@Mixin(ServerExplosion.class)
public abstract class ExplosionMixin implements ExplosionRays.Exposed {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Vec3 center;
    @Shadow @Final private float radius;
    @Shadow @Final private ExplosionDamageCalculator damageCalculator;
    @Shadow @Final private @Nullable Entity source;

    /** This explosion's ray hits, cast across the pool, waiting to go into its affected blocks. */
    @Unique private ExplosionRays.@Nullable Hits skein$rays;

    /** Exposure of the entities in range, while this explosion is working out damage and knockback. */
    @Unique private ExplosionRays.@Nullable Exposure skein$exposure;

    @Override
    public ExplosionRays.Exposure skein$exposure() {
        return this.skein$exposure;
    }

    /**
     * Once the entity list is final -- after the detonate event has had its
     * say -- works out exposure for the non-living entities in it on the pool;
     * see {@link ExplosionRays.Exposure}.
     */
    @Inject(method = "hurtEntities(Ljava/util/List;)V", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraftforge/event/ForgeEventFactory;onExplosionDetonate(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/Explosion;Ljava/util/List;Ljava/util/List;D)V",
            remap = false))
    private void skein$exposeInParallel(List<BlockPos> blocks, CallbackInfo ci, @Local(ordinal = 1) List<Entity> nearby) {
        this.skein$exposure = SkeinConfig.Live.explosionHooks && SkeinConfig.Live.parallelExplosionRays
                && Skein.pool() != null && Skein.pool().size() > 1 && !DimensionTicker.isQuarantined(this.level)
                ? ExplosionRays.exposure((Explosion) this, this.level, this.center, nearby)
                : null;
    }

    @WrapOperation(method = "hurtEntities(Ljava/util/List;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/ServerExplosion;getSeenPercent(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/Entity;)F"))
    private float skein$exposedDamage(Vec3 centre, Entity entity, Operation<Float> original) {
        ExplosionRays.Exposure exposure = this.skein$exposure;
        float seen = exposure == null ? Float.NaN : exposure.lookup(this.level, centre, entity);
        return Float.isNaN(seen) ? original.call(centre, entity) : seen;
    }

    @Inject(method = "hurtEntities(Ljava/util/List;)V", at = @At("RETURN"))
    private void skein$forgetExposure(List<BlockPos> blocks, CallbackInfo ci) {
        this.skein$exposure = null;
    }

    @Unique
    private long @Nullable [] skein$visited;
    @Unique private int skein$minX;
    @Unique private int skein$minY;
    @Unique private int skein$minZ;

    /**
     * The chunks this explosion's rays have read, at most four: a blast of
     * ordinary size spans a 2x2 patch of chunks at most, and larger ones fall
     * back to the level for the rest.
     */
    @Unique private final long[] skein$heldPositions = new long[4];
    @Unique private final @Nullable LevelChunk[] skein$heldChunks = new LevelChunk[4];
    @Unique private int skein$held;

    /** Whether this explosion's ray loop has been decided: cast on the pool, or left to run. */
    @Unique private boolean skein$decided;

    /** Starts the rays: the deduplication cube around the centre. */
    @Inject(method = "calculateExplodedPositions", at = @At("HEAD"))
    private void skein$beginRays(CallbackInfoReturnable<List<BlockPos>> cir) {
        this.skein$visited = null;
        this.skein$held = 0;
        this.skein$rays = null;
        this.skein$decided = false;
        this.skein$minX = (int) Math.floor(this.center.x) - 8;
        this.skein$minY = (int) Math.floor(this.center.y) - 8;
        this.skein$minZ = (int) Math.floor(this.center.z) - 8;
    }

    /**
     * The ray loop's bound, read before its first ray: the rays are cast on
     * the pool here -- after the explode game event, before the first draw
     * vanilla would take -- and the loop then runs no iterations; see
     * {@link ExplosionRays}.
     *
     * <p>The only place rays are cast, and a {@code @ModifyExpressionValue}
     * rather than a {@code @ModifyConstant}: a constant can be taken over by
     * only one mod, and whichever has the higher priority breaks the other --
     * Lithium 0.21's {@code world.explosions.block_raycast} takes this same
     * constant to replace the loop with its own raycast. This chains after it
     * and sees the bound as Lithium left it: anything but vanilla's 16 means
     * another mod owns the loop, so nothing is cast and nothing twice. Another
     * mod hooking the loop itself would be skipped for pool-cast rays, so then
     * the rays are left to the loop as well.
     */
    @HookFor(value = {}, yieldsUntil = "skein$feedCastRays", yields = Hooks.Feature.EXPLOSION_RAYS)
    @ModifyExpressionValue(method = "calculateExplodedPositions",
            at = @At(value = "CONSTANT", args = "intValue=16", ordinal = 1))
    private int skein$castRaysOnPool(int rays) {
        if (!this.skein$decided) {
            // The bound is read once per outer iteration; decide on the first.
            this.skein$decided = true;
            if (rays == 16) {
                // The loop is vanilla's, so it is Skein's to take over.
                ExplosionRays.markLoopHooked();
            }
            this.skein$rays = rays == 16 && SkeinConfig.Live.explosionHooks
                    && ExplosionRays.eligible(this.level, this.damageCalculator, this.source)
                    && !CarpetCompat.explosionNoBlockDamage()
                    ? ExplosionRays.cast((Explosion) this, this.level, this.damageCalculator,
                            this.center.x, this.center.y, this.center.z, this.radius)
                    : null;
        }
        return this.skein$rays != null ? 0 : rays;
    }

    /**
     * The pool's hits go into the affected blocks, each block's first hit in
     * ray order: what the set below hands over. The set itself is empty, since
     * vanilla's loop ran no iterations.
     */
    @ModifyReturnValue(method = "calculateExplodedPositions", at = @At("RETURN"))
    private List<BlockPos> skein$feedCastRays(List<BlockPos> blocks) {
        ExplosionRays.Hits rays = this.skein$rays;
        if (rays != null) {
            this.skein$rays = null;
            if (blocks instanceof ObjectArrayList<BlockPos> list) {
                rays.addTo(list);
            } else {
                ObjectArrayList<BlockPos> list = new ObjectArrayList<>();
                rays.addTo(list);
                blocks.addAll(list);
            }
        }
        return blocks;
    }

    /**
     * Keeps vanilla's callbacks and improves deduplication. The set keeps
     * first-insertion order, so the affected blocks come out in the order the
     * rays first reached them -- the order the pool's hits can be appended in
     * without a set. Vanilla's {@code HashSet} iterates in hash order, and
     * {@code interactWithBlocks} shuffles either one before anything reads it.
     */
    @HookFor({})
    @ModifyVariable(method = "calculateExplodedPositions", at = @At("STORE"), ordinal = 0, require = 0)
    private Set<BlockPos> skein$blockSet(Set<BlockPos> original) {
        return SkeinConfig.Live.explosionHooks ? new ObjectLinkedOpenHashSet<>() : original;
    }

    @HookFor({})
    @WrapOperation(method = "calculateExplodedPositions", at = @At(value = "INVOKE", target = "Ljava/util/Set;add(Ljava/lang/Object;)Z"),
            require = 0)
    private boolean skein$skipDuplicateBlock(Set<BlockPos> blocks, Object value, Operation<Boolean> original) {
        if (!SkeinConfig.Live.explosionHooks) return original.call(blocks, value);
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
     * A block read comes from the chunk the explosion already holds.
     *
     * <p>{@code Level.getBlockState} finds the chunk and asks it for the state.
     * Finding it is the expensive half -- the chunk source, Skein's per-thread
     * cache and whatever wraps {@code getChunk}, for every one of some thirty
     * thousand ray steps -- and measured on a column of 202 fuse-1 TNT it was
     * nearly a third of each explosion. The first read of each chunk still goes
     * through {@code getChunk} exactly as vanilla's would, with its loading and
     * its ticket; later reads of the same chunk skip straight to what vanilla
     * would have been handed. A held chunk is the chunk object itself, not a
     * copy of its blocks, so {@code interactWithBlocks}, which reads each
     * affected block as the blocks before it break, holds chunks the same way.
     *
     * <p>Optional in both methods: a read it does not take over is simply a
     * vanilla read. Lithium's raycast owns the first method's reads.
     *
     * <p>Carpet redirects this same call to answer bedrock while its
     * {@code explosionNoBlockDamage} rule is on, and this wrapper sits around
     * that redirect, so skipping {@code original} would skip carpet too and
     * blow up blocks the rule protects. While the rule is on, every read goes
     * through.
     */
    @HookFor({})
    @WrapOperation(method = {"calculateExplodedPositions", "interactWithBlocks"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"),
            require = 0)
    private BlockState skein$blockFromHeldChunk(ServerLevel level, BlockPos pos, Operation<BlockState> original) {
        if (!SkeinConfig.Live.explosionHooks || level.isDebug() || !level.isInValidBounds(pos)
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

    @Inject(method = "interactWithBlocks", at = @At("HEAD"))
    private void skein$beginBlocks(List<BlockPos> blocks, CallbackInfo ci) {
        this.skein$held = 0;
    }

    /**
     * Shuffles the affected blocks with the source every draw would pick: a
     * blast's fourteen hundred draws each asked the thread context which
     * source that was, and the answer cannot change inside the shuffle. The
     * draws themselves are then taken in bulk; see {@link BulkRandom}.
     */
    @WrapOperation(method = "interactWithBlocks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/Util;shuffle(Ljava/util/List;Lnet/minecraft/util/RandomSource;)V"))
    private void skein$shuffleFromOneSource(List<?> blocks, RandomSource random, Operation<Void> original) {
        if (!SkeinConfig.Live.explosionHooks) {
            original.call(blocks, random);
            return;
        }
        RandomSource source = random instanceof SkeinRandom skein ? skein.current() : random;
        if (!BulkRandom.confined(this.level) || !BulkRandom.shuffle(blocks, source)) {
            original.call(blocks, source);
        }
    }

    @Inject(method = "calculateExplodedPositions", at = @At("RETURN"))
    private void skein$releaseRayChunks(CallbackInfoReturnable<List<BlockPos>> cir) {
        skein$releaseHeldChunks();
    }

    @Inject(method = "interactWithBlocks", at = @At("RETURN"))
    private void skein$releaseBlockChunks(List<BlockPos> blocks, CallbackInfo ci) {
        skein$releaseHeldChunks();
    }

    @Unique
    private void skein$releaseHeldChunks() {
        // An explosion object can outlive its tick; do not pin chunks with it.
        java.util.Arrays.fill(this.skein$heldChunks, 0, this.skein$held, null);
        this.skein$held = 0;
    }

    @HookFor({})
    @WrapOperation(method = "calculateExplodedPositions", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"),
            require = 0)
    private FluidState skein$fluidFromBlock(ServerLevel level, BlockPos pos, Operation<FluidState> original,
                                           @Local BlockState state) {
        // LevelChunkSection.getFluidState reads this same palette entry again.
        // There is no callback or world write between the two reads. Debug
        // worlds synthesize block states, so retain their separate fluid read.
        return SkeinConfig.Live.explosionHooks && !level.isDebug() ? state.getFluidState() : original.call(level, pos);
    }
}
