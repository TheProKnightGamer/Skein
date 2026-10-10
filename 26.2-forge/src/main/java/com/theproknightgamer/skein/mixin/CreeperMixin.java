package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.SkeinExplosive;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** The radius the creeper will explode with; see {@link SkeinExplosive}. */
@Mixin(Creeper.class)
public abstract class CreeperMixin implements SkeinExplosive {

    @Shadow private int explosionRadius;

    @Shadow public abstract boolean isPowered();

    @Override
    public float skein$explosionRadius() {
        return this.explosionRadius * (this.isPowered() ? 2.0F : 1.0F);
    }
}
