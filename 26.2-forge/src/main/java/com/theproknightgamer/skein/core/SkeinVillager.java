package com.theproknightgamer.skein.core;

/** Whether a villager may roll new trades on its next tick -- levelling up, or its first; see {@code VillagerMixin}. */
public interface SkeinVillager {
    boolean skein$levelsUpThisTick();
}
