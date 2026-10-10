package com.theproknightgamer.skein.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Forge's {@code ChunkHolder.currentlyLoading} -- the chunk being promoted to
 * FULL, handed out to bypass the future chain -- is package-private on 1.20.1.
 * It is a Forge field, so its name is not remapped.
 */
@Mixin(ChunkHolder.class)
public interface ChunkHolderAccess {
    @Accessor(value = "currentlyLoading", remap = false)
    LevelChunk skein$currentlyLoading();
}
