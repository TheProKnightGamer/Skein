package com.theproknightgamer.skein.core;

import com.mojang.datafixers.util.Either;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;

/**
 * 1.21's {@code ChunkHolder.getChunkIfPresent}: what a holder has already
 * published at a status, without scheduling anything. On 1.20.1 that is the
 * status future's value if it has completed, and nothing otherwise.
 */
public final class ChunkPublished {
    private ChunkPublished() {}

    public static ChunkAccess at(ChunkHolder holder, ChunkStatus status) {
        Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> either =
                holder.getFutureIfPresentUnchecked(status).getNow(null);
        return either == null ? null : either.left().orElse(null);
    }
}
