package com.theproknightgamer.skein.mixin.compat;

import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SuperChunk's mid-tick chunk tasks (from C2ME): after every entity tick and
 * every scheduled block or fluid tick, the server thread runs one task from the
 * level's chunk executor -- a load, a promotion to FULL that adds a chunk's
 * entities and block entities, a ticket change. On the thread that owns the
 * level, between its own ticks, that is what vanilla does between ticks, only
 * sooner. Inside a parallel cell it is not: the server thread runs cells of the
 * level it coordinates, and every other worker is in that level's chunk system
 * at the same moment. Seen in Create Chronicles as a structure search's chunk
 * request (Supplementaries' road signs) refused halfway through an entity pass.
 *
 * <p>Inside a cell the task waits instead, for the next call outside one or the
 * end of the tick. Priority 1500 so it applies after SuperChunk's mixin has added
 * the method.
 */
@HookFor(Hooks.Feature.IN_LEVEL)
@Mixin(value = MinecraftServer.class, priority = 1500)
public abstract class SuperChunkMidTickMixin {
    @Inject(method = "executeTasksMidTick(Lnet/minecraft/server/level/ServerLevel;)V", at = @At("HEAD"),
            cancellable = true, remap = false)
    private void skein$notInsideACell(ServerLevel level, CallbackInfo ci) {
        if (TickContext.deferring()) {
            ci.cancel();
        }
    }
}
