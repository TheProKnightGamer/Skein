package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The burnout history, which vanilla keeps in a {@code static WeakHashMap}.
 *
 * <p>{@code RedstoneTorchBlock.RECENT_TOGGLES} maps a level to a plain
 * {@code ArrayList} of every time a torch flipped in the last sixty ticks, and
 * it is written from the torch's own tick and from its neighbour updates. It is
 * a {@code HashMap} with no synchronisation of any kind.
 *
 * <p>Two threads flipping torches at the same time is therefore the classic
 * one: a concurrent resize corrupts the bucket chain and the next lookup never
 * returns. Not an exception, not corruption anyone can see -- a thread that
 * spins forever inside {@code HashMap.get}, a barrier that never closes, and a
 * server that stops without saying anything. Redstone that flips constantly,
 * which is exactly what a 0-tick contraption is, hits it fastest.
 *
 * <p>Both entry points are put behind one lock. It is a leaf -- nothing under it
 * touches the world, takes another lock, or calls into mod code -- so there is
 * nothing for it to deadlock against, and a torch burning out is rare enough
 * that a global lock costs nothing.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
@Mixin(RedstoneTorchBlock.class)
public abstract class RedstoneTorchBlockMixin {

    @Unique
    private static final ReentrantLock SKEIN_TOGGLES = new ReentrantLock();

    /**
     * Vanilla reads the list here and then trims entries older than sixty ticks
     * in a loop. The trim happens inside the lock instead, and the wrapper
     * hands back null so the loop that followed has nothing left to do.
     */
    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object skein$trimRecentToggles(Map toggles, Object level, Operation<Object> original) {
        long now = ((Level) level).getGameTime();
        SKEIN_TOGGLES.lock();
        try {
            List list = (List) original.call(toggles, level);
            while (list != null && !list.isEmpty()
                    && now - ((ToggleAccessor) list.get(0)).skein$when() > 60L) {
                list.remove(0);
            }
        } finally {
            SKEIN_TOGGLES.unlock();
        }
        return null;
    }

    @WrapMethod(method = "isToggledTooFrequently")
    private static boolean skein$guardRecentToggles(Level level, BlockPos pos, boolean logToggle,
                                                    Operation<Boolean> original) {
        SKEIN_TOGGLES.lock();
        try {
            return original.call(level, pos, logToggle);
        } finally {
            SKEIN_TOGGLES.unlock();
        }
    }
}
