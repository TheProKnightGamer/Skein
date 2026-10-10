package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.Skein;
import net.minecraft.world.level.Level;

/** Versioned optional bridge. Whole-level ownership never implies cell-worker ownership. */
public final class SuperChunkBridge {
    private SuperChunkBridge() {}

    public static int apiVersion() { return 1; }

    /** A config reload cannot retrofit a wrapper into an already constructed level. */
    public static boolean supportsParallelTicks() {
        if (!SkeinConfig.Live.threadSafeRandom) return false;
        var server = Skein.server();
        if (server != null) for (var level : server.getAllLevels()) {
            if (!(level.getRandom() instanceof SkeinRandom)) return false;
        }
        return true;
    }

    /** A cell may read its own level; structural changes still go through DeferredWork. */
    public static boolean ownsCell(Level level) {
        return level != null && SkeinConfig.Live.enabled && TickContext.owns(level) && TickContext.deferring();
    }

    /** Only the serialized cache-miss path may drive chunk lifecycle callbacks. */
    public static boolean ownsChunkTasks(Level level) {
        return ownsCell(level) && ((SkeinLevel) level).skein$chunkLoadLock().isHeldByCurrentThread();
    }

    public static boolean phaseActive() {
        return DimensionTicker.phaseActive() || TickContext.deferring();
    }

    /** Preserve command transaction ordering across both the cell and dimension barriers. */
    public static boolean deferCommand(Level level, Runnable command) {
        if (!ownsCell(level)) return false;
        DeferredWork.defer(level, () -> {
            if (!DimensionTicker.deferPastBarrier(command)) command.run();
        });
        return true;
    }
}
