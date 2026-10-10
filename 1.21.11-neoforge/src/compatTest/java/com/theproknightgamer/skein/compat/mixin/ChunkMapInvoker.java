package com.theproknightgamer.skein.compat.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkMap.class)
public interface ChunkMapInvoker {
    @Accessor("entityMap")
    Int2ObjectMap<?> skeinTest$entityMap();
}
