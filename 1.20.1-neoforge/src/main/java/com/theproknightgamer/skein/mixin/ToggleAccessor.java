package com.theproknightgamer.skein.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code RedstoneTorchBlock$Toggle} keeps its timestamp package-private, and
 * {@link RedstoneTorchBlockMixin} has to read it to do vanilla's own trim.
 */
@Mixin(targets = "net.minecraft.world.level.block.RedstoneTorchBlock$Toggle")
public interface ToggleAccessor {

    @Accessor("when")
    long skein$when();
}
