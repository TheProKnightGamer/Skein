package com.theproknightgamer.skein.core;

/**
 * Whether a living entity's next data sync will rebuild its effect particles,
 * which posts NeoForge's {@code EffectParticleModificationEvent} once per
 * active effect. See {@code TrackingPass}.
 */
public interface SkeinEffects {
    boolean skein$effectsPending();
}
