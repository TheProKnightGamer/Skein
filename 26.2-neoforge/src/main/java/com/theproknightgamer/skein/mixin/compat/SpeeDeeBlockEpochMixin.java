package com.theproknightgamer.skein.mixin.compat;

import com.theproknightgamer.skein.core.SpeeDeeCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** The block epoch of SpeeDeeEntity 1.0.2's TickCache; see {@link SpeeDeeCache.Epoch}. */
@Mixin(targets = "com.theproknightgamer.speedeeentity.ofem.TickCache", remap = false)
public abstract class SpeeDeeBlockEpochMixin implements SpeeDeeCache.Epoch {
    @Shadow(remap = false) public long blockEpoch;

    @Override
    public void skein$endBlockEpoch() {
        this.blockEpoch++;
    }
}
