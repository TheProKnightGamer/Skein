package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.SkeinPoiIndex;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The rest of the point-of-interest index, under the same lock
 * {@link SectionStorageMixin} put its section map behind.
 *
 * <p>Three things live up here that the base class knows nothing about. The
 * village distance graph is a {@code Long2ByteOpenHashMap} and a levelled
 * priority queue, and {@code sectionsToVillage} drains pending updates into it
 * on the way past -- which every iron golem, cat and raider goal does on the
 * tick it asks whether it is near a village. The sections themselves are plain
 * maps, added to and removed from as beds and job sites appear. And a ticket on
 * a record is a bare {@code freeTickets--}, so two villagers passing the
 * has-space filter at the same moment both claim the one bed and one of them
 * lives with a home nobody thinks it has.
 *
 * <p>{@code getInChunk} is wrapped rather than just locked because it hands
 * back a lazy stream: the records are read out of the section when the caller
 * consumes it, which is long after any lock taken here would have been
 * released. Collecting it inside the lock puts the iteration back where it can
 * be protected, and costs a list per chunk of a scan.
 *
 * <p>The searches that finish inside the call -- {@code findClosest},
 * {@code find}, {@code getRandom}, {@code getCountInRange} and
 * {@code getInRange} -- are locked whole. In vanilla they reach the sections
 * through {@code getInChunk} and would be covered anyway; Lithium's
 * {@code ai.poi} overwrites every one of them with a walk over the section map
 * that never goes through a locked method, and so would any other mod that
 * speeds them up. {@code getInRange} is collected inside the lock for the
 * reason {@code getInChunk} is.
 *
 * <p>Not wrapped: {@code ensureLoadedAndValid}, which ends in
 * {@code getChunk}. Holding a lock across chunk loading is the one thing this
 * lock must not do; the {@code getOrLoad} calls inside it are covered on their
 * own.
 */
@HookFor(Hooks.Feature.IN_LEVEL)
@Mixin(PoiManager.class)
public abstract class PoiManagerMixin {

    @Unique
    private ReentrantLock skein$lock() {
        return ((SkeinPoiIndex) (Object) this).skein$poiLock();
    }

    @WrapMethod(method = "getInChunk")
    private Stream<PoiRecord> skein$lockInChunk(Predicate<Holder<PoiType>> typePredicate,
                                                ChunkPos posChunk,
                                                PoiManager.Occupancy status,
                                                Operation<Stream<PoiRecord>> original) {
        ReentrantLock lock = skein$lock();
        lock.lock();
        try {
            return original.call(typePredicate, posChunk, status).toList().stream();
        } finally {
            lock.unlock();
        }
    }

    @WrapMethod(method = "take")
    private Optional<BlockPos> skein$lockTake(Predicate<Holder<PoiType>> typePredicate,
                                              BiPredicate<Holder<PoiType>, BlockPos> combinedTypePosPredicate,
                                              BlockPos pos,
                                              int distance,
                                              Operation<Optional<BlockPos>> original) {
        // The whole search, not just the ticket: finding a record with space
        // and taking that space have to be one step or two villagers take the
        // same one.
        ReentrantLock lock = skein$lock();
        lock.lock();
        try {
            return original.call(typePredicate, combinedTypePosPredicate, pos, distance);
        } finally {
            lock.unlock();
        }
    }

    @WrapMethod(method = "release")
    private boolean skein$lockRelease(BlockPos pos, Operation<Boolean> original) {
        ReentrantLock lock = skein$lock();
        lock.lock();
        try {
            return original.call(pos);
        } finally {
            lock.unlock();
        }
    }

    @WrapMethod(method = "add")
    private void skein$lockAdd(BlockPos pos, Holder<PoiType> type, Operation<Void> original) {
        ReentrantLock lock = skein$lock();
        lock.lock();
        try {
            original.call(pos, type);
        } finally {
            lock.unlock();
        }
    }

    @WrapMethod(method = "remove(Lnet/minecraft/core/BlockPos;)V")
    private void skein$lockRemovePoi(BlockPos pos, Operation<Void> original) {
        ReentrantLock lock = skein$lock();
        lock.lock();
        try {
            original.call(pos);
        } finally {
            lock.unlock();
        }
    }

    @WrapMethod(method = "exists")
    private boolean skein$lockExists(BlockPos pos, Predicate<Holder<PoiType>> typePredicate,
                                     Operation<Boolean> original) {
        ReentrantLock lock = skein$lock();
        lock.lock();
        try {
            return original.call(pos, typePredicate);
        } finally {
            lock.unlock();
        }
    }

    @WrapMethod(method = "getType")
    private Optional<Holder<PoiType>> skein$lockGetType(BlockPos pos,
                                                        Operation<Optional<Holder<PoiType>>> original) {
        ReentrantLock lock = skein$lock();
        lock.lock();
        try {
            return original.call(pos);
        } finally {
            lock.unlock();
        }
    }

    @WrapMethod(method = "sectionsToVillage")
    private int skein$lockSectionsToVillage(SectionPos sectionPos, Operation<Integer> original) {
        ReentrantLock lock = skein$lock();
        lock.lock();
        try {
            return original.call(sectionPos);
        } finally {
            lock.unlock();
        }
    }

    /**
     * The override, not the base method: this is where the distance graph is
     * told a section changed, and that half runs outside the lock the base
     * method takes for the dirty set.
     */
    @WrapMethod(method = "setDirty")
    private void skein$lockSetDirty(long sectionPos, Operation<Void> original) {
        ReentrantLock lock = skein$lock();
        lock.lock();
        try {
            original.call(sectionPos);
        } finally {
            lock.unlock();
        }
    }

    @WrapMethod(method = "tick")
    private void skein$lockTick(BooleanSupplier aheadOfTime, Operation<Void> original) {
        ReentrantLock lock = skein$lock();
        lock.lock();
        try {
            original.call(aheadOfTime);
        } finally {
            lock.unlock();
        }
    }

    @WrapMethod(method = "checkConsistencyWithBlocks")
    private void skein$lockCheckConsistency(SectionPos sectionPos, LevelChunkSection levelChunkSection,
                                            Operation<Void> original) {
        ReentrantLock lock = skein$lock();
        lock.lock();
        try {
            original.call(sectionPos, levelChunkSection);
        } finally {
            lock.unlock();
        }
    }

    @WrapMethod(method = "getInRange")
    private Stream<PoiRecord> skein$lockInRange(Predicate<Holder<PoiType>> typePredicate, BlockPos pos, int distance,
                                                PoiManager.Occupancy status, Operation<Stream<PoiRecord>> original) {
        ReentrantLock lock = skein$lock();
        lock.lock();
        try {
            return original.call(typePredicate, pos, distance, status).toList().stream();
        } finally {
            lock.unlock();
        }
    }

    @WrapMethod(method = "getCountInRange")
    private long skein$lockCountInRange(Predicate<Holder<PoiType>> typePredicate, BlockPos pos, int distance,
                                        PoiManager.Occupancy status, Operation<Long> original) {
        ReentrantLock lock = skein$lock();
        lock.lock();
        try {
            return original.call(typePredicate, pos, distance, status);
        } finally {
            lock.unlock();
        }
    }

    @WrapMethod(method = "findClosest(Ljava/util/function/Predicate;Lnet/minecraft/core/BlockPos;ILnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)Ljava/util/Optional;")
    private Optional<BlockPos> skein$lockFindClosest(Predicate<Holder<PoiType>> typePredicate, BlockPos pos,
                                                     int distance, PoiManager.Occupancy status,
                                                     Operation<Optional<BlockPos>> original) {
        ReentrantLock lock = skein$lock();
        lock.lock();
        try {
            return original.call(typePredicate, pos, distance, status);
        } finally {
            lock.unlock();
        }
    }

    @WrapMethod(method = "findClosest(Ljava/util/function/Predicate;Ljava/util/function/Predicate;Lnet/minecraft/core/BlockPos;ILnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)Ljava/util/Optional;")
    private Optional<BlockPos> skein$lockFindClosestAt(Predicate<Holder<PoiType>> typePredicate,
                                                       Predicate<BlockPos> posPredicate, BlockPos pos, int distance,
                                                       PoiManager.Occupancy status,
                                                       Operation<Optional<BlockPos>> original) {
        ReentrantLock lock = skein$lock();
        lock.lock();
        try {
            return original.call(typePredicate, posPredicate, pos, distance, status);
        } finally {
            lock.unlock();
        }
    }

    @WrapMethod(method = "findClosestWithType")
    private Optional<Pair<Holder<PoiType>, BlockPos>> skein$lockFindClosestWithType(
            Predicate<Holder<PoiType>> typePredicate, BlockPos pos, int distance, PoiManager.Occupancy status,
            Operation<Optional<Pair<Holder<PoiType>, BlockPos>>> original) {
        ReentrantLock lock = skein$lock();
        lock.lock();
        try {
            return original.call(typePredicate, pos, distance, status);
        } finally {
            lock.unlock();
        }
    }

    @WrapMethod(method = "find")
    private Optional<BlockPos> skein$lockFind(Predicate<Holder<PoiType>> typePredicate, Predicate<BlockPos> posPredicate,
                                              BlockPos pos, int distance, PoiManager.Occupancy status,
                                              Operation<Optional<BlockPos>> original) {
        ReentrantLock lock = skein$lock();
        lock.lock();
        try {
            return original.call(typePredicate, posPredicate, pos, distance, status);
        } finally {
            lock.unlock();
        }
    }

    @WrapMethod(method = "getRandom")
    private Optional<BlockPos> skein$lockGetRandom(Predicate<Holder<PoiType>> typePredicate,
                                                   Predicate<BlockPos> posPredicate, PoiManager.Occupancy status,
                                                   BlockPos pos, int distance, RandomSource random,
                                                   Operation<Optional<BlockPos>> original) {
        ReentrantLock lock = skein$lock();
        lock.lock();
        try {
            return original.call(typePredicate, posPredicate, status, pos, distance, random);
        } finally {
            lock.unlock();
        }
    }
}
