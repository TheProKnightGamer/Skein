package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.SkeinSequenceRandom;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomSequence;
import net.minecraft.world.RandomSequences;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Where every loot roll in the game gets its randomness, and two separate
 * pieces of shared state on the way to it.
 *
 * <p>The first is the table of sequences itself, a plain
 * {@code Object2ObjectOpenHashMap} that {@code get} inserts into with
 * {@code computeIfAbsent}. Looking a sequence up is therefore a structural
 * write, and not one the deferral machinery has any way of seeing: it comes
 * from a mob dying rather than from a registry change. It fires the first time
 * anything rolls a given table, which on a young world is hundreds of times
 * over the first minutes, and two cells crossing the load factor together
 * rehash the table under each other -- which loses a mapping, so the sequence
 * is silently reseeded and the save stops reproducing it, or leaves a probe
 * chain that never terminates. A concurrent map ends it, exactly as
 * {@link ClassInstanceMultiMapMixin} does for the entity index.
 *
 * <p>The second is what the lookup hands back, which is the sequence's single
 * {@code XoroshiroRandomSource} rather than a copy -- see
 * {@link SkeinSequenceRandom} for why that one is locked rather than made
 * per-thread.
 *
 * <p>Both are server-wide, not per level: the overworld's instance is passed to
 * every other dimension as it is built, so this is reachable with the in-level
 * phases off and dimension threading alone.
 */
@Mixin(RandomSequences.class)
public abstract class RandomSequencesMixin {

    @Shadow @Final @Mutable private Map<ResourceLocation, RandomSequence> sequences;

    @Unique
    private final ReentrantLock skein$lock = new ReentrantLock();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void skein$concurrentSequenceTable(long seed, CallbackInfo ci) {
        this.sequences = new ConcurrentHashMap<>(this.sequences);
    }

    @WrapMethod(method = "get")
    private RandomSource skein$lockSequenceDraws(ResourceLocation location, Operation<RandomSource> original) {
        return new SkeinSequenceRandom(original.call(location), this.skein$lock);
    }
}
