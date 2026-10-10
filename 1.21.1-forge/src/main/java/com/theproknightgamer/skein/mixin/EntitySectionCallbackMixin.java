package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.DeferredWork;
import com.theproknightgamer.skein.core.SkeinDetachable;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.world.level.Level;
import com.theproknightgamer.skein.core.DimensionTicker;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The two entity-registry mutations that fire from inside an entity's own tick.
 *
 * <p>{@code onMove} moves an entity between {@code EntitySection}s, which
 * writes the level's section map and its sorted section-id set -- the same set
 * every other thread is iterating to answer area queries. {@code onRemove}
 * unhooks the entity from the id and uuid maps and from tracking. Both are
 * level-wide writes reachable from any cell, so both wait for the barrier.
 *
 * <p>Deferring {@code onMove} leaves the spatial index up to one section stale
 * for the remainder of the pass. That is harmless: sections are an index, not
 * the truth, and area queries still filter on each entity's real bounding box.
 *
 * <p>Deferring {@code onRemove} is not harmless on its own, and this is the one
 * place the deferral has to do more than park a call. Vanilla's removal ends by
 * setting the entity's callback to {@code NULL}, which is what makes every later
 * {@code onMove} on a dead entity a no-op. Park the removal and that guarantee
 * goes with it: a mob picking an item up removes it from one cell while the item
 * is moving in another, and if the removal is applied first the move puts the
 * discarded item straight back into a section. So the detach happens
 * immediately, in the same order vanilla does it, and only the teardown waits --
 * with parked moves dropped once their entity is detached.
 *
 * <p>Both are parked as an opcode and a reference rather than as a lambda.
 * A section transfer is the most frequent deferred write in the game -- every
 * entity that crosses a 16-block boundary produces one -- and a method
 * reference for each of them is a per-entity allocation on the hottest path
 * the mod has.
 */
@HookFor(Hooks.Feature.IN_LEVEL)
@Mixin(targets = "net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback")
public abstract class EntitySectionCallbackMixin implements SkeinDetachable {

    @Shadow @Final private EntityAccess entity;

    @Shadow private long currentSectionKey;

    @Unique
    private volatile boolean skein$detached;

    @Override
    public boolean skein$detached() {
        return this.skein$detached;
    }

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void skein$deferMove(CallbackInfo ci) {
        if (!TickContext.deferring()) {
            return;
        }
        if (SectionPos.asLong(this.entity.blockPosition()) == this.currentSectionKey) {
            // Same section: vanilla's body is a no-op, so let it run rather than
            // park an entry for every entity that moved this tick.
            return;
        }
        if (skein$foreign()) {
            // Another level's entity: its section manager is that level's,
            // and this pass's buffer is drained with that level still running.
            if (DimensionTicker.deferPastBarrier(() -> {
                if (!this.skein$detached) {
                    ((EntityInLevelCallback) this).onMove();
                }
            })) {
                ci.cancel();
            }
            return;
        }
        if (DeferredWork.deferMove((EntityInLevelCallback) this)) {
            ci.cancel();
        }
    }

    /** Whether the entity belongs to a level other than the one this thread is ticking. */
    @Unique
    private boolean skein$foreign() {
        Level current = TickContext.current();
        return current != null && this.entity instanceof Entity owner && owner.level() != current;
    }

    /**
     * When the removal parked below is replayed, vanilla's body ends by
     * setting the entity's callback to {@code NULL} -- which was already done
     * when it was parked, and which by now may overwrite the callback of a
     * level the entity has since joined: a player whose removal from one
     * dimension waited for the barrier, and whose arrival in another was
     * applied first. That write is skipped for a detached callback.
     */
    @WrapOperation(method = "onRemove", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/EntityAccess;setLevelCallback(Lnet/minecraft/world/level/entity/EntityInLevelCallback;)V"))
    private void skein$keepLaterCallback(EntityAccess entity, EntityInLevelCallback callback, Operation<Void> original) {
        if (!this.skein$detached) {
            original.call(entity, callback);
        }
    }

    @Inject(method = "onRemove", at = @At("HEAD"), cancellable = true)
    private void skein$deferRemove(Entity.RemovalReason reason, CallbackInfo ci) {
        if (!TickContext.deferring()) {
            return;
        }
        if (this.skein$detached) {
            // Already parked. Vanilla would be running against a NULL callback
            // by now and doing nothing, so do nothing.
            ci.cancel();
            return;
        }
        boolean foreign = skein$foreign();
        if (foreign ? DimensionTicker.deferPastBarrier(() -> ((EntityInLevelCallback) this).onRemove(reason))
                : DeferredWork.deferRemove((EntityInLevelCallback) this, reason)) {
            this.skein$detached = true;
            // The last thing vanilla's onRemove does, done first: from here the
            // entity's own callback is NULL, so nothing it does for the rest of
            // the pass can reach this object.
            this.entity.setLevelCallback(EntityInLevelCallback.NULL);
            ci.cancel();
        }
    }
}
