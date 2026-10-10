package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.theproknightgamer.skein.core.HookFor;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * A level's saved data is a plain {@code HashMap} from name to data, created
 * on first ask. Mods ask from wherever they need it -- an entity's tick, a
 * machine's tick -- and keep server-wide data in the overworld's, which any
 * dimension's tick reaches. Two threads creating entries at once corrupt the
 * map, so lookups and inserts hold a lock. A miss reads the file under it;
 * that happens once per name.
 *
 * <p>Optional: without it, mods share the map as they did before.
 */
@HookFor({})
@Mixin(DimensionDataStorage.class)
public abstract class DimensionDataStorageMixin {
    @Unique
    private final Object skein$lock = new Object();

    @WrapMethod(method = "computeIfAbsent")
    private <T extends SavedData> T skein$computeIfAbsent(SavedDataType<T> type, Operation<T> original) {
        synchronized (this.skein$lock) {
            return original.call(type);
        }
    }

    @WrapMethod(method = "get")
    private <T extends SavedData> T skein$get(SavedDataType<T> type, Operation<T> original) {
        synchronized (this.skein$lock) {
            return original.call(type);
        }
    }

    @WrapMethod(method = "set")
    private <T extends SavedData> void skein$set(SavedDataType<T> type, T data, Operation<Void> original) {
        synchronized (this.skein$lock) {
            original.call(type, data);
        }
    }
}
