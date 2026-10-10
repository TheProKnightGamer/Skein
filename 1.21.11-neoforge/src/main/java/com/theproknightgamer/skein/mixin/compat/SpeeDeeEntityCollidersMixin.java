package com.theproknightgamer.skein.mixin.compat;

import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.SpeeDeeCompat;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.util.List;

/**
 * Includes queries from vanilla's slow collision path, which bypass OFEMUtil.
 * The collider list is shared by the level's threads for a tick; see
 * {@code SpeeDeeCache.colliders}.
 *
 * <p>Only a saving: without it each thread builds the list in its own
 * clearance cache, which {@code SpeeDeeTickCacheMixin} already makes per
 * thread.
 */
@HookFor({})
@Mixin(targets = "com.theproknightgamer.speedeeentity.ofem.EntityColliders", remap = false)
public abstract class SpeeDeeEntityCollidersMixin {
    @WrapMethod(method = "candidates")
    private static List<Entity> skein$sharedColliders(Level level, @Coerce Object shared,
                                                      Operation<List<Entity>> original) {
        if (!Hooks.allows(Hooks.Feature.IN_LEVEL)) {
            return original.call(level, shared);
        }
        return ((SpeeDeeCompat.LevelAccess) level).skein$speedeeCaches().colliders(level.getGameTime(),
                SpeeDeeEpochAccessor.skein$epoch().get(), builder -> original.call(level, builder));
    }
}
