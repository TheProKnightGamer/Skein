package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.theproknightgamer.skein.core.DeferredWork;
import com.theproknightgamer.skein.core.DimensionTicker;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

/**
 * An ender pearl landing, which acts on its thrower from the pearl's tick.
 *
 * <p>The landing dismounts the thrower, moves it to the pearl -- across
 * dimensions if the pearl went through a portal, which vanilla allows -- and
 * hurts it. None of that belongs to the pearl's cell or even to the pearl's
 * level: a thrower in another dimension is being ticked by another thread at
 * that moment, and the teleport removes it from that level's entity manager,
 * chunk tracking and player list from here. Within one level a thrower a
 * hundred blocks away is in another cell, where a mob may be hitting it.
 *
 * <p>So the whole landing is parked -- past the dimension barrier when the
 * thrower is in another level and dimensions are ticking in parallel, at the
 * pass barrier otherwise -- and replayed from the position the pearl landed
 * at, with nothing else running. The pearl is discarded by the replay, as
 * vanilla discards it. What moves is only the moment within the tick; a
 * landing that happens on the level's own thread with no pass open is vanilla
 * untouched.
 */
@HookFor({Hooks.Feature.DIMENSIONS, Hooks.Feature.IN_LEVEL})
@Mixin(ThrownEnderpearl.class)
public abstract class ThrownEnderpearlMixin {

    @WrapMethod(method = "onHit")
    private void skein$landWithNothingElseRunning(HitResult result, Operation<Void> original) {
        ThrownEnderpearl self = (ThrownEnderpearl) (Object) this;
        Entity owner = self.getOwner();
        if (owner != null) {
            Level level = self.level();
            boolean acrossLevels = owner.level() != level && DimensionTicker.phaseActive();
            if (acrossLevels || TickContext.deferring()) {
                Vec3 landed = self.position();
                Runnable landing = () -> {
                    if (!self.isRemoved()) {
                        self.setPos(landed);
                        original.call(result);
                    }
                };
                if (DimensionTicker.deferPastBarrier(landing) || DeferredWork.park(landing)) {
                    return;
                }
            }
        }
        original.call(result);
    }
}
