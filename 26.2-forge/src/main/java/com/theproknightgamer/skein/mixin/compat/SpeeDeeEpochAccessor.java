package com.theproknightgamer.skein.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.atomic.AtomicLong;

/** SpeeDeeEntity's collider epoch, bumped when a collider enters or leaves a section. */
@Mixin(targets = "com.theproknightgamer.speedeeentity.ofem.EntityColliders", remap = false)
public interface SpeeDeeEpochAccessor {
    @Accessor(value = "EPOCH", remap = false)
    static AtomicLong skein$epoch() {
        throw new AssertionError("mixin not applied");
    }
}
