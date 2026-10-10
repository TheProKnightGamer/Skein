package com.theproknightgamer.skein.core;

import net.minecraft.world.level.Level;

import java.lang.reflect.Constructor;

/** Optional bridge for SpeeDeeEntity's original and indexed collision implementations. */
public final class SpeeDeeCompat {
    private SpeeDeeCompat() {}

    /** Implemented on Level only while SpeeDeeEntity is installed. */
    public interface LevelAccess {
        SpeeDeeCache skein$speedeeCaches();
    }

    public static Object cacheFor(Level level) {
        return ((LevelAccess) level).skein$speedeeCaches().get(level.getGameTime());
    }

    // Resolve lazily, after mixin preparation. No compile/runtime dependency on
    // SpeeDeeEntity when absent, and no reflection on the collision hot path.
    private static final class Factory {
        static final Constructor<?> CONSTRUCTOR = constructor();

        private static Constructor<?> constructor() {
            try {
                return Class.forName("com.theproknightgamer.speedeeentity.ofem.TickCache")
                        .getConstructor();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Unsupported SpeeDeeEntity TickCache layout", e);
            }
        }
    }

    public static SpeeDeeCache.Entry newCache() {
        try {
            return (SpeeDeeCache.Entry) Factory.CONSTRUCTOR.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not create a worker's SpeeDeeEntity cache", e);
        }
    }
}
