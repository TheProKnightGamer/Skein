package com.theproknightgamer.skein.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.theproknightgamer.skein.core.CreateCompat;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Every member and total of a Create kinetic network; see {@link CreateCompat}.
 * The reads hold the lock too: they iterate the member map another cell may be
 * adding to.
 */
@HookFor({Hooks.Feature.BLOCK_ENTITIES, Hooks.Feature.ENTITIES})
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.KineticNetwork", remap = false)
public abstract class CreateKineticNetworkMixin {

    @WrapMethod(method = "initFromTE")
    private void skein$initFromTE(float maxStress, float currentStress, int members, Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call(maxStress, currentStress, members);
        }
    }

    @WrapMethod(method = "addSilently")
    private void skein$addSilently(@Coerce Object be, float lastCapacity, float lastStress, Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call(be, lastCapacity, lastStress);
        }
    }

    @WrapMethod(method = "add")
    private void skein$add(@Coerce Object be, Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call(be);
        }
    }

    @WrapMethod(method = "updateCapacityFor")
    private void skein$updateCapacityFor(@Coerce Object be, float capacity, Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call(be, capacity);
        }
    }

    @WrapMethod(method = "updateStressFor")
    private void skein$updateStressFor(@Coerce Object be, float stress, Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call(be, stress);
        }
    }

    @WrapMethod(method = "remove")
    private void skein$remove(@Coerce Object be, Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call(be);
        }
    }

    @WrapMethod(method = "sync")
    private void skein$sync(Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call();
        }
    }

    @WrapMethod(method = "updateCapacity")
    private void skein$updateCapacity(Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call();
        }
    }

    @WrapMethod(method = "updateStress")
    private void skein$updateStress(Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call();
        }
    }

    @WrapMethod(method = "updateNetwork")
    private void skein$updateNetwork(Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call();
        }
    }

    @WrapMethod(method = "calculateCapacity")
    private float skein$calculateCapacity(Operation<Float> original) {
        synchronized (CreateCompat.LOCK) {
            return original.call();
        }
    }

    @WrapMethod(method = "calculateStress")
    private float skein$calculateStress(Operation<Float> original) {
        synchronized (CreateCompat.LOCK) {
            return original.call();
        }
    }

    @WrapMethod(method = "getActualCapacityOf")
    private float skein$getActualCapacityOf(@Coerce Object be, Operation<Float> original) {
        synchronized (CreateCompat.LOCK) {
            return original.call(be);
        }
    }

    @WrapMethod(method = "getActualStressOf")
    private float skein$getActualStressOf(@Coerce Object be, Operation<Float> original) {
        synchronized (CreateCompat.LOCK) {
            return original.call(be);
        }
    }

    @WrapMethod(method = "getSize")
    private int skein$getSize(Operation<Integer> original) {
        synchronized (CreateCompat.LOCK) {
            return original.call();
        }
    }
}
