package com.theproknightgamer.skein.mixin;

import java.util.function.Consumer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** {@code forEachBlockTickingChunk} is package-private; the random-tick redirect needs to call it. */
@Mixin(ChunkMap.class)
public interface ChunkMapInvoker {
    @Invoker("forEachBlockTickingChunk")
    void skein$forEachBlockTickingChunk(Consumer<LevelChunk> action);
}
