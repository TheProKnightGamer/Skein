package com.theproknightgamer.skein.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.theproknightgamer.skein.core.CreateCompat;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.util.Map;
import java.util.Set;

/**
 * Create's redstone-link frequencies: one map per level of every link on
 * every frequency, joined and left from the links' first tick and unload and
 * re-walked on every signal change. The link blocks themselves tick on the
 * level thread (see {@link CreateCompat#LONG_REACH}); this covers the
 * contraptions and other blocks that join a frequency from their own ticks.
 */
@HookFor({Hooks.Feature.BLOCK_ENTITIES, Hooks.Feature.ENTITIES})
@Pseudo
@Mixin(targets = "com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler", remap = false)
public abstract class CreateRedstoneLinkMixin {

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

    @WrapMethod(method = "getNetworkOf")
    private Set<?> skein$getNetworkOf(LevelAccessor level, @Coerce Object actor, Operation<Set<?>> original) {
        synchronized (CreateCompat.LOCK) {
            return original.call(level, actor);
        }
    }

    @WrapMethod(method = "addToNetwork")
    private void skein$addToNetwork(LevelAccessor level, @Coerce Object actor, Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call(level, actor);
        }
    }

    @WrapMethod(method = "removeFromNetwork")
    private void skein$removeFromNetwork(LevelAccessor level, @Coerce Object actor, Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call(level, actor);
        }
    }

    @WrapMethod(method = "updateNetworkOf")
    private void skein$updateNetworkOf(LevelAccessor level, @Coerce Object actor, Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call(level, actor);
        }
    }

    @WrapMethod(method = "networksIn")
    private Map<?, ?> skein$networksIn(LevelAccessor level, Operation<Map<?, ?>> original) {
        synchronized (CreateCompat.LOCK) {
            return original.call(level);
        }
    }
}
