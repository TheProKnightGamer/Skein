package com.theproknightgamer.skein.mixin.compat;

import com.theproknightgamer.skein.core.SpeeDeeCache;
import com.theproknightgamer.skein.core.SpeeDeeCompat;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Level ownership prevents cached collider entities from retaining unloaded worlds. */
@Mixin(Level.class)
public abstract class SpeeDeeLevelMixin implements SpeeDeeCompat.LevelAccess {
    @Unique private final SpeeDeeCache skein$speedeeCaches = new SpeeDeeCache(SpeeDeeCompat::newCache);

    @Override
    public SpeeDeeCache skein$speedeeCaches() {
        return skein$speedeeCaches;
    }
}
