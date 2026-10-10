package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.Demotions;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.SkeinRefusal;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Consumer;

/**
 * Keeps a {@link SkeinRefusal} out of vanilla's entity crash handler, which
 * would report it as the entity crashing -- and discard the entity under
 * NeoForge's {@code removeErroringEntities}. The entity's tick ends there, as
 * it would have with any exception; the entity stays. The block-entity half is
 * on {@link BoundTickingBlockEntityMixin}.
 *
 * <p>A failure inside a parallel pass is handled here too, by demoting the
 * entity's type (see {@link Demotions#absorb}). Priority 100 puts this wrapper
 * nearest the tick, inside any other mod's wrapper of the same call: Neruina
 * wraps it to catch every throwable and suspend the entity, which it would
 * otherwise do to a refusal, or to a type that only fails in parallel.
 */
@HookFor(Hooks.Feature.IN_LEVEL)
@Mixin(value = Level.class, priority = 100)
public abstract class TickGuardMixin {

    @WrapOperation(method = "guardEntityTick", at = @At(value = "INVOKE",
            target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"))
    private <T> void skein$survivesRefusal(Consumer<T> action, T entity, Operation<Void> original) {
        try {
            original.call(action, entity);
        } catch (RuntimeException | Error thrown) {
            if (!Demotions.absorb(entity, (Level) (Object) this, thrown)) {
                throw thrown;
            }
        }
    }
}
