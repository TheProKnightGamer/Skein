package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.SkeinEffects;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

/** Exposes the pending effect-particle rebuild; see {@link SkeinEffects}. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityEffectsMixin implements SkeinEffects {

    @Shadow private boolean effectsDirty;

    @Shadow @Final private Map<Holder<MobEffect>, MobEffectInstance> activeEffects;

    @Override
    public boolean skein$effectsPending() {
        return this.effectsDirty && !this.activeEffects.isEmpty();
    }
}
