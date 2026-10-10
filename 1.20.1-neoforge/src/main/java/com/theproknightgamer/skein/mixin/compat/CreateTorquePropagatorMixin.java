package com.theproknightgamer.skein.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.theproknightgamer.skein.core.CreateCompat;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Create's map from network id to network, one per level, inside one static
 * {@code HashMap}. Looking a network up creates it if it is missing, and two
 * members in different cells doing that at once made two networks with the
 * same id. See {@link CreateCompat}.
 *
 * <p>{@code KineticNetwork} here is a compile-time stub of Create's class;
 * it is not packaged.
 */
@HookFor({Hooks.Feature.BLOCK_ENTITIES, Hooks.Feature.ENTITIES})
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.TorquePropagator", remap = false)
public abstract class CreateTorquePropagatorMixin {

    @WrapMethod(method = "getOrCreateNetworkFor")
    private KineticNetwork skein$getOrCreateNetworkFor(@Coerce Object be, Operation<KineticNetwork> original) {
        synchronized (CreateCompat.LOCK) {
            return original.call(be);
        }
    }

    @WrapMethod(method = "onLoadWorld")
    private void skein$onLoadWorld(LevelAccessor level, Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call(level);
        }
    }

    @WrapMethod(method = "onUnloadWorld")
    private void skein$onUnloadWorld(LevelAccessor level, Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call(level);
        }
    }
}
