package com.theproknightgamer.skein.compat.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

@Mixin(ChunkMap.class)
public interface ChunkMapInvoker {
    @Invoker("getChunks")
    Iterable<ChunkHolder> skeinTest$getChunks();

    @Accessor("entityMap")
    Int2ObjectMap<?> skeinTest$entityMap();
}
