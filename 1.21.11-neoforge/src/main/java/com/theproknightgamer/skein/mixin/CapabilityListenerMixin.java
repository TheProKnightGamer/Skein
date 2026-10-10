package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.theproknightgamer.skein.core.HookFor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.capabilities.ICapabilityInvalidationListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * NeoForge keeps a level's capability-cache listeners in one two-level fastutil
 * map. Machines and pipes register into it the first time they look at a
 * neighbour, and every block entity placed or removed invalidates through it
 * -- both from block-entity and entity ticks, so from every cell of a pass at
 * once. Each access holds a lock per level. Invalidation runs the mods'
 * listeners under it, which may register again; the lock is re-entrant.
 *
 * <p>Optional: without it, the map is shared as it was before.
 */
@HookFor({})
@Mixin(value = ServerLevel.class, remap = false)
public abstract class CapabilityListenerMixin {
    @Unique
    private final Object skein$capabilityLock = new Object();

    @WrapMethod(method = "invalidateCapabilities(Lnet/minecraft/core/BlockPos;)V")
    private void skein$invalidateAt(BlockPos pos, Operation<Void> original) {
        synchronized (this.skein$capabilityLock) {
            original.call(pos);
        }
    }

    @WrapMethod(method = "invalidateCapabilities(Lnet/minecraft/world/level/ChunkPos;)V")
    private void skein$invalidateChunk(ChunkPos pos, Operation<Void> original) {
        synchronized (this.skein$capabilityLock) {
            original.call(pos);
        }
    }

    @WrapMethod(method = "registerCapabilityListener")
    private void skein$register(BlockPos pos, ICapabilityInvalidationListener listener, Operation<Void> original) {
        synchronized (this.skein$capabilityLock) {
            original.call(pos, listener);
        }
    }

    @WrapMethod(method = "cleanCapabilityListenerReferences")
    private void skein$clean(Operation<Void> original) {
        synchronized (this.skein$capabilityLock) {
            original.call();
        }
    }
}
