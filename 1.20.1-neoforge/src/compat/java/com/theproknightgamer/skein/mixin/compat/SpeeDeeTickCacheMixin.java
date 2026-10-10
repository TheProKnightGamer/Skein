package com.theproknightgamer.skein.mixin.compat;

import com.theproknightgamer.skein.core.SpeeDeeCache;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes only fields common to the legacy and indexed TickCache layouts. */
@Mixin(targets = "com.theproknightgamer.speedeeentity.ofem.TickCache", remap = false)
public abstract class SpeeDeeTickCacheMixin implements SpeeDeeCache.Entry {
    @Shadow public long tick;
    @Shadow @Final public Long2ByteOpenHashMap map;

    @Override
    public void skein$clear(long now) {
        tick = now;
        map.clear();
    }
}
