package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.ChunkScan;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** An entry in the chunk tick's list, a package-private record. */
@Mixin(targets = "net.minecraft.server.level.ServerChunkCache$ChunkAndHolder")
public interface ChunkAndHolderAccessor extends ChunkScan.Entry {

    @Override
    @Accessor("holder")
    ChunkHolder skein$holder();

    @Override
    @Accessor("chunk")
    LevelChunk skein$chunk();
}
