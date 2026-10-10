package com.theproknightgamer.skein.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.theproknightgamer.skein.core.HookFor;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Iron's Spellbooks keeps the entities a spell has just hit, so they skip the
 * knockback, in one static {@code HashMap}: filled from the spell entities'
 * ticks, read and emptied by a knockback event handler. Two area spells in
 * different cells or dimensions at once throw {@code ConcurrentModificationException}
 * from it, and every spell type that does would be demoted in turn. Both sides
 * hold one lock.
 *
 * <p>Optional: without it, a spell type that throws is demoted as any other.
 */
@HookFor({})
@Pseudo
@Mixin(targets = "io.redspace.ironsspellbooks.damage.DamageSources", remap = false)
public abstract class IronsKnockbackMixin {
    @Unique
    private static final Object SKEIN_LOCK = new Object();

    @WrapMethod(method = "ignoreNextKnockback")
    private static void skein$ignoreNextKnockback(LivingEntity entity, Operation<Void> original) {
        synchronized (SKEIN_LOCK) {
            original.call(entity);
        }
    }

    @WrapMethod(method = "cancelKnockback")
    private static void skein$cancelKnockback(@Coerce Object event, Operation<Void> original) {
        synchronized (SKEIN_LOCK) {
            original.call(event);
        }
    }
}
