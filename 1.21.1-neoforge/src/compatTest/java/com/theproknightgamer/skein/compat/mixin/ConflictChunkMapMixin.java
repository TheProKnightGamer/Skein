package com.theproknightgamer.skein.compat.mixin;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

/**
 * A typical mod at the default priority that overwrites a method Skein hooks,
 * with a body that no longer makes the call Skein wraps. Same behaviour as
 * vanilla, through the boxed map interface.
 */
@Mixin(value = ChunkMap.class, priority = 1000)
abstract class ConflictChunkMapMixin {
    @Shadow @Final private Long2ByteMap chunkTypeCache;

    /**
     * @author skein conflict test
     * @reason simulate another mod rewriting a method Skein hooks
     */
    @Overwrite
    @SuppressWarnings("deprecation")
    private byte markPosition(ChunkPos chunkPos, ChunkType chunkType) {
        Byte previous = ((Map<Long, Byte>) this.chunkTypeCache).put(chunkPos.toLong(),
                (byte) (chunkType == ChunkType.PROTOCHUNK ? -1 : 1));
        return previous == null ? 0 : previous;
    }
}
