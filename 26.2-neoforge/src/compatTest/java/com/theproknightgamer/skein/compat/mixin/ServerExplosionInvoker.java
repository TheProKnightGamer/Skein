package com.theproknightgamer.skein.compat.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * 1.21.11's {@code ServerExplosion.explode} casts, damages and breaks in one
 * call. The explosion suites need the steps apart -- the affected blocks and the
 * entity effects on unchanged terrain, then the breaking -- as 1.21.1's
 * {@code explode} and {@code finalizeExplosion} gave them.
 */
@Mixin(ServerExplosion.class)
public interface ServerExplosionInvoker {
    @Invoker("calculateExplodedPositions")
    List<BlockPos> skeinTest$calculateExplodedPositions();

    @Invoker("hurtEntities")
    void skeinTest$hurtEntities(List<BlockPos> blocks);

    @Invoker("interactWithBlocks")
    void skeinTest$interactWithBlocks(List<BlockPos> blocks);
}
