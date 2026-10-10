package com.theproknightgamer.skein.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler;
import com.theproknightgamer.skein.core.CreateCompat;
import com.theproknightgamer.skein.core.HookFor;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * A redstone-link frequency for a plain item is interned in a static
 * {@code IdentityHashMap}, filled the first time any link anywhere uses that
 * item. See {@link CreateCompat}; {@code Frequency} here is a compile-time
 * stub of Create's class.
 */
@HookFor({})
@Pseudo
@Mixin(targets = "com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler$Frequency", remap = false)
public abstract class CreateFrequencyMixin {

    @WrapMethod(method = "of")
    private static RedstoneLinkNetworkHandler.Frequency skein$of(ItemStack stack,
                                                                 Operation<RedstoneLinkNetworkHandler.Frequency> original) {
        synchronized (CreateCompat.LOCK) {
            return original.call(stack);
        }
    }
}
