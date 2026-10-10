package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.PathTypeEntry;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The pathfinding cache, made safe to share.
 *
 * <p>{@code PathTypeCache} is a 4096-entry direct-mapped cache over two plain
 * arrays -- a packed position and the {@code PathType} computed for it -- and
 * every navigating mob hits it hundreds of times per path. Vanilla writes the
 * two halves separately, which is fine when only one thread is ever inside a
 * level.
 *
 * <p>It is not fine here, and not for the reason it looks like. Two threads
 * ticking cells far enough apart never ask about the same block, but 4096 slots
 * across a whole world means they collide in the <em>table</em> constantly. Two
 * interleaved writes can leave one thread's position sitting next to the other
 * thread's path type, and the reader has no way to tell: it looks up its own
 * block, finds a matching position, and is handed the wrong answer. A mob that
 * believes lava is walkable walks into it.
 *
 * <p>So a slot holds one immutable object instead of two fields. A reader
 * either sees an entry or does not, and an entry always carries the position it
 * was computed for. The cost is one small allocation per cache miss -- next to
 * the block lookups a miss already does, which is why the entry is created here
 * rather than a seqlock being wrapped around vanilla's arrays.
 *
 * <p>The two halves replace vanilla's lookup and invalidation outright, so they
 * only ever act together: if either is missing both hand back to vanilla's
 * arrays, and the in-level phases that needed them are off.
 */
@HookFor(Hooks.Feature.IN_LEVEL)
@Mixin(PathTypeCache.class)
public abstract class PathTypeCacheMixin {

    @Unique
    private static final int SKEIN_MASK = 4095;

    @Unique
    private final AtomicReferenceArray<PathTypeEntry> skein$slots = new AtomicReferenceArray<>(4096);

    @WrapMethod(method = "getOrCompute")
    private PathType skein$getOrCompute(BlockGetter level, BlockPos pos, Operation<PathType> original) {
        if (!Hooks.allows(Hooks.Feature.IN_LEVEL)) {
            return original.call(level, pos);
        }
        long packed = pos.asLong();
        int slot = skein$index(packed);
        PathTypeEntry entry = this.skein$slots.get(slot);
        if (entry != null && entry.position() == packed) {
            return entry.type();
        }
        PathType type = WalkNodeEvaluatorInvoker.skein$getPathTypeFromState(level, pos);
        this.skein$slots.set(slot, new PathTypeEntry(packed, type));
        return type;
    }

    @WrapMethod(method = "invalidate")
    private void skein$invalidate(BlockPos pos, Operation<Void> original) {
        if (!Hooks.allows(Hooks.Feature.IN_LEVEL)) {
            original.call(pos);
            return;
        }
        long packed = pos.asLong();
        int slot = skein$index(packed);
        PathTypeEntry entry = this.skein$slots.get(slot);
        if (entry != null && entry.position() == packed) {
            // Compare-and-set rather than a blind clear: another thread may
            // already have replaced this slot with an entry for a different
            // block, and dropping that would just cost it a recompute.
            this.skein$slots.compareAndSet(slot, entry, null);
        }
    }

    @Unique
    private static int skein$index(long position) {
        return (int) HashCommon.mix(position) & SKEIN_MASK;
    }
}
