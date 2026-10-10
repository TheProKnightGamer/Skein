package com.theproknightgamer.skein.core;

import java.util.concurrent.locks.ReentrantLock;

/** The per-raid lock {@code RaidMixin} adds, for the raider-side entry points that need it too. */
public interface SkeinRaid {
    ReentrantLock skein$raidLock();
}
