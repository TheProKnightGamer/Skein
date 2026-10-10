package com.theproknightgamer.skein.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.theproknightgamer.skein.core.CreateCompat;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * A deployer using an item on a block records the drops it causes in a static
 * map keyed by position alone, shared by every deployer in every dimension; a
 * lost removal makes later drops at that position vanish. Each use holds
 * {@link CreateCompat#LOCK}.
 */
@HookFor({Hooks.Feature.BLOCK_ENTITIES, Hooks.Feature.DIMENSIONS})
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.deployer.DeployerHandler", remap = false)
public abstract class CreateDeployerMixin {

    @WrapMethod(method = "safeOnUse")
    private static InteractionResult skein$safeOnUse(BlockState state, Level level, BlockPos pos, Player player,
                                                     InteractionHand hand, BlockHitResult hit,
                                                     Operation<InteractionResult> original) {
        synchronized (CreateCompat.LOCK) {
            return original.call(state, level, pos, player, hand, hit);
        }
    }
}
