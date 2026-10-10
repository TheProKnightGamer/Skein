package com.theproknightgamer.skein.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.theproknightgamer.skein.core.CreateCompat;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Create's rotation walks. Adding or removing a kinetic block walks its whole
 * network, rewriting every member's speed, source and network and breaking
 * blocks whose speeds conflict; two walks crossing at once leave a network
 * half re-powered. Each walk holds {@link CreateCompat#LOCK} from start to
 * finish.
 */
@HookFor({Hooks.Feature.BLOCK_ENTITIES, Hooks.Feature.ENTITIES})
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.RotationPropagator", remap = false)
public abstract class CreateRotationMixin {

    @WrapMethod(method = "handleAdded")
    private static void skein$handleAdded(Level level, BlockPos pos, @Coerce Object added, Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call(level, pos, added);
        }
    }

    @WrapMethod(method = "handleRemoved")
    private static void skein$handleRemoved(Level level, BlockPos pos, @Coerce Object removed,
                                            Operation<Void> original) {
        synchronized (CreateCompat.LOCK) {
            original.call(level, pos, removed);
        }
    }
}
