package com.theproknightgamer.skein.core;

/** Uses real threads and the same parking flag as the production worker. */
public final class TickContext {
    public static final class Worker extends Thread {
        public volatile boolean parked;

        public Worker(Runnable body, String name) {
            super(body, name);
            setDaemon(true);
        }
    }

    static void reportStuck(Worker[] workers) {
    }
}
