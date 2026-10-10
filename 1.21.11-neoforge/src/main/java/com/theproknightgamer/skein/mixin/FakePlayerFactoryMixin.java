package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.authlib.GameProfile;
import com.theproknightgamer.skein.core.HookFor;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * NeoForge hands out fake players from one static {@code HashMap} with
 * {@code computeIfAbsent}, called by machines from their ticks: two
 * dimensions' quarries, or two cells' deployers, at once. A concurrent
 * {@code computeIfAbsent} on a {@code HashMap} throws or corrupts it, so the
 * factory holds a lock.
 *
 * <p>Optional: without it, the map is shared as it was before.
 */
@HookFor({})
@Mixin(value = FakePlayerFactory.class, remap = false)
public abstract class FakePlayerFactoryMixin {
    @Unique
    private static final Object SKEIN_LOCK = new Object();

    @WrapMethod(method = "get")
    private static FakePlayer skein$get(ServerLevel level, GameProfile profile, Operation<FakePlayer> original) {
        synchronized (SKEIN_LOCK) {
            return original.call(level, profile);
        }
    }

    @WrapMethod(method = "unloadLevel")
    private static void skein$unloadLevel(ServerLevel level, Operation<Void> original) {
        synchronized (SKEIN_LOCK) {
            original.call(level);
        }
    }
}
