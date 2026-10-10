package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.SkeinExplosive;
import net.minecraft.world.entity.projectile.LargeFireball;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** The power the fireball will explode with; see {@link SkeinExplosive}. */
@Mixin(LargeFireball.class)
public abstract class LargeFireballMixin implements SkeinExplosive {

    @Shadow private int explosionPower;

    @Override
    public float skein$explosionRadius() {
        return this.explosionPower;
    }
}
