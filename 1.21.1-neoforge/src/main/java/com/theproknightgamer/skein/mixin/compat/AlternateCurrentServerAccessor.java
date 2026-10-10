package com.theproknightgamer.skein.mixin.compat;

import com.theproknightgamer.skein.core.AlternateCurrentCompat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The world's storage access, which Alternate Current's wire handler is built from. */
@Mixin(MinecraftServer.class)
public interface AlternateCurrentServerAccessor extends AlternateCurrentCompat.StorageAccess {
    @Override
    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess skein$storageSource();
}
