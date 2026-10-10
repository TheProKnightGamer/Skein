package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.SkeinTicker;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * What actually lives in {@code Level.blockEntityTickers} is this wrapper, not
 * the bound ticker, so it has to pass the question through. It is rebindable --
 * the ticker underneath is swapped when the block at that position changes --
 * which is exactly why nothing is cached here.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$RebindableTickingBlockEntityWrapper")
public abstract class RebindableTickerMixin implements SkeinTicker {

    @Shadow private TickingBlockEntity ticker;

    @Override
    public BlockEntity skein$blockEntity() {
        return this.ticker instanceof SkeinTicker inner ? inner.skein$blockEntity() : null;
    }
}
