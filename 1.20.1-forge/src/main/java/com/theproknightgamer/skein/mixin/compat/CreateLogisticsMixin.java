package com.theproknightgamer.skein.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.theproknightgamer.skein.core.CreateCompat;
import com.theproknightgamer.skein.core.HookFor;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

import java.util.UUID;

/**
 * Create 6's server-wide logistics networks, written as links load, unload and
 * break in any dimension. See {@link CreateCompat}.
 */
@HookFor({})
@Pseudo
@Mixin(targets = "com.simibubi.create.content.logistics.packagerLink.GlobalLogisticsManager", remap = false)
public abstract class CreateLogisticsMixin {

    @WrapMethod(method = "levelLoaded")
    private void skein$levelLoaded(LevelAccessor level, Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call(level);
        }
    }

    @WrapMethod(method = "linkAdded")
    private void skein$linkAdded(UUID network, GlobalPos pos, UUID owner, Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call(network, pos, owner);
        }
    }

    @WrapMethod(method = "linkLoaded")
    private void skein$linkLoaded(UUID network, GlobalPos pos, Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call(network, pos);
        }
    }

    @WrapMethod(method = "linkRemoved")
    private void skein$linkRemoved(UUID network, GlobalPos pos, Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call(network, pos);
        }
    }

    @WrapMethod(method = "linkInvalidated")
    private void skein$linkInvalidated(UUID network, GlobalPos pos, Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call(network, pos);
        }
    }

    @WrapMethod(method = "getUnloadedLinkCount")
    private int skein$getUnloadedLinkCount(UUID network, Operation<Integer> original) {
        synchronized (CreateCompat.LOCK) {
            return original.call(network);
        }
    }

    @WrapMethod(method = "hasQueuedPromises")
    private boolean skein$hasQueuedPromises(UUID network, Operation<Boolean> original) {
        synchronized (CreateCompat.LOCK) {
            return original.call(network);
        }
    }

    @WrapMethod(method = "tick")
    private void skein$tick(Level level, Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call(level);
        }
    }
}
