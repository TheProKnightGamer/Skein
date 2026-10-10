package com.theproknightgamer.skein.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code WalkNodeEvaluator.getPathTypeFromState} is protected static, and
 * {@link PathTypeCacheMixin} has to call it directly: the vanilla method it
 * replaces reaches it through the very arrays that mixin exists to stop using.
 */
@Mixin(WalkNodeEvaluator.class)
public interface WalkNodeEvaluatorInvoker {

    @Invoker("getPathTypeFromState")
    static PathType skein$getPathTypeFromState(BlockGetter level, BlockPos pos) {
        throw new AssertionError("mixin invoker");
    }
}
