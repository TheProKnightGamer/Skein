package com.theproknightgamer.skein.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.theproknightgamer.skein.core.CreateCompat;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

import java.util.List;

/**
 * Brass tunnels split items using two static scratch collections, cleared and
 * refilled by whichever tunnel is distributing. Two tunnels doing it at once,
 * in any cell or any dimension, insert into each other's targets.
 */
@HookFor({Hooks.Feature.BLOCK_ENTITIES, Hooks.Feature.DIMENSIONS})
@Pseudo
@Mixin(targets = "com.simibubi.create.content.logistics.tunnel.BrassTunnelBlockEntity", remap = false)
public abstract class CreateBrassTunnelMixin {

    @WrapMethod(method = "distribute")
    private void skein$distribute(List<?> targets, Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call(targets);
        }
    }
}
