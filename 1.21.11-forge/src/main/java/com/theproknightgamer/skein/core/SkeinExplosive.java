package com.theproknightgamer.skein.core;

/**
 * An entity whose explosion radius is its own data rather than a constant --
 * a creeper's and a large fireball's can be set to 127 through NBT. See
 * {@code EntityTicker#isPinned}.
 */
public interface SkeinExplosive {
    float skein$explosionRadius();
}
