package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.SkeinRaid;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.locks.ReentrantLock;

/**
 * A raid's membership, which its raiders write from their own ticks rather than
 * from the raid's.
 *
 * <p>Wave membership is a plain {@code HashMap} of plain {@code HashSet}s, the
 * wave leaders are another {@code HashMap} and the heroes are a
 * {@code HashSet}, and none of the three is reached only from
 * {@code Raids.tick}. A raider that wandered out and came back rejoins from its
 * own {@code aiStep}, a patrol leader recruits the raiders around it from a
 * goal, a raider that dies removes itself, hands over its leader slot and
 * credits its killer -- and every hit any raider takes recomputes the boss bar,
 * which iterates the whole membership.
 *
 * <p>Distance is no defence here, because the target is one object per raid
 * rather than a block or an entity section. A raid covers a village of radius
 * ninety-six, so two of its members are routinely in different cells of the
 * same pass. Two of them joining at once lose an insertion, and
 * {@code addWaveMob} reports success either way -- so the raider believes it is
 * in a raid that will never count it, never eject it and never clear it when it
 * dies. One joining while another is being hit iterates a set that is being
 * added to, which is a {@code ConcurrentModificationException} thrown out of
 * the middle of an entity tick; on the death path that aborts before
 * {@code super.die}, leaving a raider at zero health that nothing removes.
 *
 * <p>So every entry point an entity tick has takes one lock per raid. The
 * serial half needs none: {@code Raids.tick} runs earlier in
 * {@code ServerLevel.tick} than the entity loop and on the same thread, so
 * {@code updateRaiders} and the wave spawner never overlap a pass. The lock is
 * a leaf -- under it are health reads, plain field writes and the boss bar's
 * packet send -- and {@code joinRaid}'s spawn branch, the one thing here that
 * puts an entity into the world, is left outside it.
 */
@HookFor(Hooks.Feature.IN_LEVEL)
@Mixin(Raid.class)
public abstract class RaidMixin implements SkeinRaid {

    @Unique
    private final ReentrantLock skein$raidLock = new ReentrantLock();

    @Override
    public ReentrantLock skein$raidLock() {
        return this.skein$raidLock;
    }

    /**
     * The wave set, and the running total of raider health that goes with it.
     * Both halves have to be one step or a lost add still moves the total.
     */
    @WrapMethod(method = "addWaveMob(ILnet/minecraft/world/entity/raid/Raider;Z)Z")
    private boolean skein$lockAddWaveMob(int wave, Raider raider, boolean isRecruited,
                                         Operation<Boolean> original) {
        this.skein$raidLock.lock();
        try {
            return original.call(wave, raider, isRecruited);
        } finally {
            this.skein$raidLock.unlock();
        }
    }

    @WrapMethod(method = "removeFromRaid")
    private void skein$lockRemoveFromRaid(Raider raider, boolean wanderedOutOfRaid,
                                          Operation<Void> original) {
        this.skein$raidLock.lock();
        try {
            original.call(raider, wanderedOutOfRaid);
        } finally {
            this.skein$raidLock.unlock();
        }
    }

    @WrapMethod(method = "removeLeader")
    private void skein$lockRemoveLeader(int wave, Operation<Void> original) {
        this.skein$raidLock.lock();
        try {
            original.call(wave);
        } finally {
            this.skein$raidLock.unlock();
        }
    }

    /**
     * The write side of the leader map. {@code Raider.pickUpItem} checks for a
     * leader and then calls this, and {@code RaiderMixin} holds the lock across
     * both, so two raiders taking a banner at once cannot both lead the wave.
     */
    @WrapMethod(method = "setLeader")
    private void skein$lockSetLeader(int wave, Raider raider, Operation<Void> original) {
        this.skein$raidLock.lock();
        try {
            original.call(wave, raider);
        } finally {
            this.skein$raidLock.unlock();
        }
    }

    @WrapMethod(method = "getLeader")
    private Raider skein$lockGetLeader(int wave, Operation<Raider> original) {
        // Read side of the leader map, which a raider dying in another cell is
        // removing from at the same moment.
        this.skein$raidLock.lock();
        try {
            return original.call(wave);
        } finally {
            this.skein$raidLock.unlock();
        }
    }

    @WrapMethod(method = "addHeroOfTheVillage")
    private void skein$lockAddHero(Entity player, Operation<Void> original) {
        this.skein$raidLock.lock();
        try {
            original.call(player);
        } finally {
            this.skein$raidLock.unlock();
        }
    }

    /**
     * The boss bar's sum, which walks every wave set. Reached from every hit a
     * raider takes as well as from the two membership writes above, so it is
     * the iteration most likely to be open when one of them lands.
     */
    @WrapMethod(method = "getHealthOfLivingRaiders")
    private float skein$lockHealthOfLivingRaiders(Operation<Float> original) {
        this.skein$raidLock.lock();
        try {
            return original.call();
        } finally {
            this.skein$raidLock.unlock();
        }
    }
}
