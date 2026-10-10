package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.BulkRandom;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.atomic.AtomicLong;

/** The seed {@link BulkRandom} advances in bulk. */
@Mixin(LegacyRandomSource.class)
public interface LegacyRandomSourceAccessor extends BulkRandom.Seeded {

    @Override
    @Accessor("seed")
    AtomicLong skein$seed();
}
