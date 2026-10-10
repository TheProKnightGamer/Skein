package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.ChunkScan;
import net.minecraft.server.level.ChunkHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.BitSet;

/** The three fields {@code broadcastChanges} tests before doing anything; see {@link ChunkScan#changed}. */
@Mixin(ChunkHolder.class)
public interface ChunkHolderChangesAccessor extends ChunkScan.Changes {

    @Override
    @Accessor("hasChangedSections")
    boolean skein$hasChangedSections();

    @Override
    @Accessor("skyChangedLightSectionFilter")
    BitSet skein$skyChangedLightSectionFilter();

    @Override
    @Accessor("blockChangedLightSectionFilter")
    BitSet skein$blockChangedLightSectionFilter();
}
