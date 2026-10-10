package com.theproknightgamer.skein.compat;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.core.TickContext;
import net.neoforged.fml.LogicalSide;
import net.neoforged.fml.util.thread.EffectiveSide;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

/**
 * What other mods see of a Skein worker. EffectiveSide reads the thread group,
 * which a worker takes from the server thread that starts the pool; Cupboard
 * reads the name, and holds back any entity added from a thread without
 * "server" in it.
 */
final class ThreadIdentityVerification {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    static void run() {
        Set<String> workers = ConcurrentHashMap.newKeySet();
        Set<String> wrong = ConcurrentHashMap.newKeySet();
        Skein.pool().invokeAll(Skein.pool().size() * 8, i -> {
            Thread thread = Thread.currentThread();
            if (thread instanceof TickContext.Worker) {
                workers.add(thread.getName());
                if (!thread.getName().toLowerCase(Locale.ROOT).contains("server")) wrong.add(thread.getName() + ": name");
                if (EffectiveSide.get() != LogicalSide.SERVER) wrong.add(thread.getName() + ": " + EffectiveSide.get());
            }
            // Long enough that the workers take some of the batch rather than the caller running it all.
            LockSupport.parkNanos(1_000_000);
        });
        check(!workers.isEmpty(), "no task of the batch ran on a worker");
        check(wrong.isEmpty(), "workers other mods would not take for the server: " + wrong);
        Skein.LOGGER.info("Thread identity verified: {} workers seen, each named as a server thread and on the server side",
                workers.size());
    }
}
