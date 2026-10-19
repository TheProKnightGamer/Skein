package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.Skein;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Alternate Current replaces redstone wire's power logic with one
 * {@code WireHandler} per level, and that handler is a scratchpad: a node map,
 * a search queue and an update queue, filled and emptied by every wire change.
 * A daylight detector's block entity, a pressure plate under a mob, a random
 * tick -- anything in a parallel pass that changes a wire's input -- enters it,
 * and two cells entering one handler at once corrupt the network walk or leave
 * a fastutil map probing forever.
 *
 * <p>Each thread that can run a pass gets a handler of its own for each level.
 * That is exact rather than merely safe: a wire change runs from start to
 * finish on the thread that made it, starting from a cleared node map, so no
 * state is carried from one change to the next. The copies share the level
 * handler's {@code Config}, so {@code /alternatecurrent} reaches them all. A
 * lock instead would be held across neighbour updates into any mod's blocks,
 * and could deadlock against Create's.
 *
 * <p>The copies are built by Alternate Current's own constructor, on the server
 * thread before any level ticks, because that constructor loads the config
 * file, writes the mod's global on/off switch, and for a dimension other than
 * the overworld asks the overworld for its handler -- none of which a worker in
 * the middle of a pass should do. The level-thread, serial and barrier paths
 * keep using the level's own handler.
 */
public final class AlternateCurrentCompat {
    private AlternateCurrentCompat() {}

    /** Implemented on {@code ServerLevel} only while Alternate Current is installed. */
    public interface LevelAccess {
        Handlers skein$wireHandlers();

        void skein$setWireHandlers(Handlers handlers);
    }

    /** One level's per-thread handlers, built for one pool. */
    public record Handlers(SkeinPool pool, Map<Thread, Object> byThread) {}

    private static final class Reflect {
        static final Method GETTER;
        static final Constructor<?> CONSTRUCTOR;
        static final Method GET_CONFIG;
        static final Field CONFIG;
        static final Throwable FAILURE;

        static {
            Method getter = null;
            Constructor<?> constructor = null;
            Method getConfig = null;
            Field config = null;
            Throwable failure = null;
            try {
                getter = ServerLevel.class.getMethod("alternate_current$getWireHandler");
                Class<?> handler = getter.getReturnType();
                constructor = handler.getConstructor(ServerLevel.class, LevelStorageSource.LevelStorageAccess.class);
                getConfig = handler.getMethod("getConfig");
                config = handler.getDeclaredField("config");
                config.setAccessible(true);
            } catch (Throwable unsupported) {
                failure = unsupported;
            }
            GETTER = getter;
            CONSTRUCTOR = constructor;
            GET_CONFIG = getConfig;
            CONFIG = config;
            FAILURE = failure;
        }
    }

    private static volatile boolean warned;

    /**
     * Builds the handlers any level is missing, for the current pool. Called on
     * the server thread at the start of every tick, before a level ticks; with
     * Alternate Current absent, or everything built, it is one type test.
     */
    public static void prepare(MinecraftServer server, ServerLevel[] levels) {
        SkeinPool pool = Skein.pool();
        if (pool == null || levels.length == 0 || !(levels[0] instanceof LevelAccess)) {
            return;
        }
        for (ServerLevel level : levels) {
            LevelAccess access = (LevelAccess) level;
            Handlers handlers = access.skein$wireHandlers();
            if (handlers != null && handlers.pool() == pool) {
                continue;
            }
            if (Reflect.FAILURE != null) {
                warnOnce("Skein: Alternate Current's wire handler has a layout Skein does not recognise ({}); "
                        + "redstone wire changes made from parallel passes share one handler per level, as "
                        + "without this compatibility", Reflect.FAILURE);
                access.skein$setWireHandlers(new Handlers(pool, Map.of()));
                continue;
            }
            Map<Thread, Object> byThread = new HashMap<>();
            try {
                Object shared = Reflect.GETTER.invoke(level);
                Object config = Reflect.GET_CONFIG.invoke(shared);
                LevelStorageSource.LevelStorageAccess storage = ((StorageAccess) server).skein$storageSource();
                byThread.put(Thread.currentThread(), copy(level, storage, config));
                for (Thread worker : pool.threads()) {
                    byThread.put(worker, copy(level, storage, config));
                }
            } catch (Throwable failed) {
                warnOnce("Skein: could not build per-thread Alternate Current wire handlers ({}); redstone wire "
                        + "changes made from parallel passes share one handler per level", failed);
                byThread.clear();
            }
            access.skein$setWireHandlers(new Handlers(pool, Map.copyOf(byThread)));
        }
    }

    private static Object copy(ServerLevel level, LevelStorageSource.LevelStorageAccess storage, Object config)
            throws ReflectiveOperationException {
        Object handler = Reflect.CONSTRUCTOR.newInstance(level, storage);
        Reflect.CONFIG.set(handler, config);
        return handler;
    }

    /** This thread's handler for {@code level}, or null to use the level's own. */
    public static Object handlerFor(LevelAccess level) {
        Handlers handlers = level.skein$wireHandlers();
        return handlers == null ? null : handlers.byThread().get(Thread.currentThread());
    }

    /** {@code MinecraftServer}'s storage access, for Alternate Current's constructor. */
    public interface StorageAccess {
        LevelStorageSource.LevelStorageAccess skein$storageSource();
    }

    private static void warnOnce(String message, Throwable cause) {
        if (!warned) {
            warned = true;
            Skein.LOGGER.warn(message, cause.toString());
        }
    }
}
