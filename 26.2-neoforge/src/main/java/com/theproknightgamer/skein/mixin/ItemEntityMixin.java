package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.SkeinMergeable;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes vanilla's own merge test, so that an item which can never merge --
 * one that can never be picked up, say -- keeps its reach however fast it
 * moves; see {@link SkeinMergeable}.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin implements SkeinMergeable {

    @Shadow
    private boolean isMergable() {
        throw new AssertionError("shadow");
    }

    @Override
    public boolean skein$mayMerge() {
        return this.isMergable();
    }
}
