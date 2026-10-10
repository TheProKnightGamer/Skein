package com.theproknightgamer.skein.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.theproknightgamer.skein.core.CreateCompat;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * A generator changing its output -- a steam engine, a water wheel, a crank
 * let go -- detaches from its network, reattaches and re-propagates in one
 * go. Held under {@link CreateCompat#LOCK} so no other walk sees it halfway.
 */
@HookFor({Hooks.Feature.BLOCK_ENTITIES, Hooks.Feature.ENTITIES})
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity", remap = false)
public abstract class CreateGeneratorMixin {

    @WrapMethod(method = "updateGeneratedRotation")
    private void skein$updateGeneratedRotation(Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call();
        }
    }
}
