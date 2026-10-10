package com.theproknightgamer.skein.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code ChunkMap.getVisibleChunkIfPresent} is protected on Forge 1.20.1;
 * NeoForge 1.21 widens it with its own access transformer.
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccess {
    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder skein$visibleHolder(long position);
}
