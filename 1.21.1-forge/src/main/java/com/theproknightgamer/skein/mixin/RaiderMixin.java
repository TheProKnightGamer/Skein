package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.SkeinRaid;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import org.spongepowered.asm.mixin.Mixin;

import java.util.concurrent.locks.ReentrantLock;

/**
 * A raider taking up the leader's banner: "is there a leader for my wave?"
 * and "I am the leader" are two calls with the item pickup in between, and two
 * raiders of one wave in different cells can both get the first answer before
 * either gives the second. The loser then wears a banner the raid does not
 * know about, and when it dies it clears the real leader's entry. Holding the
 * raid's lock -- see {@link RaidMixin} -- across the whole pickup makes it one
 * step. Everything under it is this raider's own state, the item it picks up,
 * or a write that is parked while a pass is open.
 */
@HookFor(Hooks.Feature.IN_LEVEL)
@Mixin(Raider.class)
public abstract class RaiderMixin {

    @WrapMethod(method = "pickUpItem")
    private void skein$pickUpUnderRaidLock(ItemEntity item, Operation<Void> original) {
        Raid raid = ((Raider) (Object) this).getCurrentRaid();
        if (raid == null) {
            original.call(item);
            return;
        }
        ReentrantLock lock = ((SkeinRaid) raid).skein$raidLock();
        lock.lock();
        try {
            original.call(item);
        } finally {
            lock.unlock();
        }
    }
}
