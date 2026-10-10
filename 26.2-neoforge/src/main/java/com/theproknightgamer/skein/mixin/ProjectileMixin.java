package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.DeferredWork;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;

/**
 * Parks what a block does when a projectile hits it, in the cluster tier.
 *
 * <p>The other half of {@code EntityMixin.skein$parkEntityInside}, and the more
 * dangerous half, because the write is not bounded by the block's own position.
 * {@code TargetBlock.onProjectileHit} awards a statistic and fires an
 * advancement trigger on the projectile's <em>owner</em>, who may be hundreds of
 * blocks away or in another chunk entirely — so no separation between the two
 * projectiles, and no isolation radius around either, can keep two of them out
 * of one {@code ServerStatsCounter}. Others break blocks outright
 * ({@code ChorusFlowerBlock}, and anything in {@code #impact_projectiles} when
 * the gamerule allows it), light candles, ring bells or prime TNT.
 *
 * <p>Replayed by the coordinator at the barrier in task order: same tick, same
 * effect, nothing else running.
 */
// Priority above the default so the injections still apply when another mod has
// merged (@Overwrite) one of the target methods -- the PVP-bot mod's herobot mixins
// do that to Projectile, and Mixin refuses to inject into an equal-priority merge.
@Mixin(value = Projectile.class, priority = 1500)
public abstract class ProjectileMixin {

    @HookFor(Hooks.Feature.IN_LEVEL)
    @WrapOperation(
            method = "onHitBlock",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/world/level/block/state/BlockState;"
                            + "onProjectileHit(Lnet/minecraft/world/level/Level;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;"
                            + "Lnet/minecraft/world/phys/BlockHitResult;"
                            + "Lnet/minecraft/world/entity/projectile/Projectile;)V"))
    private void skein$parkProjectileHit(BlockState state, Level level, BlockState hitState,
                                         BlockHitResult hit, Projectile projectile, Operation<Void> original) {
        if (TickContext.clustered() && SkeinConfig.Live.hazardBlocks.contains(state.getBlock())
                && DeferredWork.park(() -> original.call(state, level, hitState, hit, projectile))) {
            return;
        }
        original.call(state, level, hitState, hit, projectile);
    }
}
