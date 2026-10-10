package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.DimensionTicker;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps a clock change in one dimension from reaching into the others while
 * they tick.
 *
 * <p>Since 26.1 day time lives in one server-wide {@link ServerClockManager}
 * rather than in the overworld's level data, and changing a clock (the night
 * skipped by sleeping, which runs inside the overworld's own tick, or a
 * {@code /time} command run from a command block or a function) ends by
 * clearing the environment-attribute tick cache of <em>every</em> level. With
 * dimensions ticking side by side those other levels are mid-tick on other
 * threads, reading that cache.
 *
 * <p>The clear is only there so the other levels pick up the new time, and
 * every level already clears its own cache at the start of its tick. So the
 * calling level's own cache is cleared at once, as vanilla does, and the
 * others are cleared at the dimension barrier: they see the new time from
 * their next tick, which is where vanilla's serial order would also put it for
 * every level that had already ticked.
 */
@Mixin(ServerClockManager.class)
public abstract class ServerClockManagerMixin {

    @HookFor(Hooks.Feature.DIMENSIONS)
    @WrapOperation(
            method = "modifyClock",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/world/attribute/EnvironmentAttributeSystem;invalidateTickCache()V"))
    private void skein$invalidateOthersAtBarrier(EnvironmentAttributeSystem system, Operation<Void> original) {
        Level current = TickContext.current();
        if (current != null && current.environmentAttributes() != system
                && DimensionTicker.deferPastBarrier(() -> original.call(system))) {
            return;
        }
        original.call(system);
    }
}
