package com.theproknightgamer.skein.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.util.Pair;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.SkeinPoiIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Lithium 0.21's {@code ai.poi} sends {@code AcquirePoi} -- every villager
 * looking for a bed or a job site -- to two methods of its own instead of
 * {@code take} and {@code findAllClosestFirstWithType}. They walk the section
 * map and take a ticket just as {@code take} does, so they go under the same
 * lock (see {@link com.theproknightgamer.skein.mixin.PoiManagerMixin}).
 *
 * <p>Optional: with that Lithium option off the methods do not exist and
 * there is nothing to lock.
 */
@HookFor({})
@Mixin(value = PoiManager.class, remap = false)
public abstract class LithiumPoiMixin {

    @WrapMethod(method = "lithium$takeAt")
    private Optional<BlockPos> skein$lockTakeAt(Predicate<Holder<PoiType>> typePredicate,
                                                BiPredicate<Holder<PoiType>, BlockPos> combinedTypePosPredicate,
                                                BlockPos pos, Operation<Optional<BlockPos>> original) {
        ReentrantLock lock = ((SkeinPoiIndex) (Object) this).skein$poiLock();
        lock.lock();
        try {
            return original.call(typePredicate, combinedTypePosPredicate, pos);
        } finally {
            lock.unlock();
        }
    }

    @WrapMethod(method = "lithium$getNClosestFirstWithType")
    private Collection<Pair<Holder<PoiType>, BlockPos>> skein$lockNClosest(
            Predicate<Holder<PoiType>> typePredicate, Predicate<BlockPos> posPredicate, BlockPos pos, int radius,
            PoiManager.Occupancy status, long limit,
            Operation<Collection<Pair<Holder<PoiType>, BlockPos>>> original) {
        ReentrantLock lock = ((SkeinPoiIndex) (Object) this).skein$poiLock();
        lock.lock();
        try {
            return java.util.List.copyOf(original.call(typePredicate, posPredicate, pos, radius, status, limit));
        } finally {
            lock.unlock();
        }
    }
}
