package com.theproknightgamer.skein.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Ritchie's Projectile Library (Create Big Cannons' projectiles, among others)
 * keeps the chunks a shell will fly into in a per-level {@code ChunkManager}:
 * fastutil open-hash sets and a queue, filled from every projectile's tick.
 * Two shells in different cells adding at once corrupt the set, and the next
 * {@code add} probes it forever -- a worker that never comes back. Both the
 * fill and the level's drain hold the manager's monitor.
 */
@HookFor(Hooks.Feature.ENTITIES)
@Pseudo
@Mixin(targets = "rbasamoyai.ritchiesprojectilelib.chunkloading.ChunkManager", remap = false)
public abstract class RplChunkManagerMixin {

    @WrapMethod(method = "queueForceLoad")
    private void skein$queueForceLoad(ChunkPos pos, Operation<Void> original) {
        synchronized (this) {
            original.call(pos);
        }
    }

    @WrapMethod(method = "tick")
    private void skein$tick(ServerLevel level, Operation<Void> original) {
        synchronized (this) {
            original.call(level);
        }
    }
}
