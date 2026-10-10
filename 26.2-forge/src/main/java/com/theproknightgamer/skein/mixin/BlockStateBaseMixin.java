package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.DeferredWork;
import com.theproknightgamer.skein.core.HazardWatch;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.HookFor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A block acting on an entity standing in it -- a pressure plate, a tripwire, an
 * end portal -- writes the world from inside an entity tick. The grid keeps two
 * concurrent entities two margins apart so those writes land in different
 * chunks; the cluster tier keeps them apart only by what they can reach each
 * other with, which for a block underfoot is nothing at all. Two items on plates
 * four blocks apart are the same level and the same instant, and they meet
 * inside {@code PalettedContainer} -- or, in different sections of one chunk, in
 * the shared heightmap where nothing checks.
 *
 * <p>So the call is parked and replayed by the coordinator at the barrier, in
 * task order, with nothing else in the level. Same tick, deterministic order,
 * one pass of latency -- the same trade as the navigation sweep. Only blocks
 * that actually write are parked, which is why a bubble column (which writes
 * nothing; it only pushes the entity) still acts in-tick.
 *
 * <p>Hooked at the callee rather than the call site because 1.21.11 makes the
 * call from a lambda inside {@code Entity.checkInsideBlocks}, where a call-site
 * redirect cannot see it. The replay re-enters this method with the coordinator
 * running and nothing clustered, so it falls straight through.
 */
@HookFor(Hooks.Feature.IN_LEVEL)
@Mixin(targets = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase")
public abstract class BlockStateBaseMixin {

    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void skein$parkEntityInside(Level level, BlockPos pos, Entity entity,
                                        InsideBlockEffectApplier applier, boolean intersects,
                                        CallbackInfo ci) {
        if (!TickContext.clustered()) {
            return;
        }
        BlockState state = (BlockState) (Object) this;
        if (!SkeinConfig.Live.hazardBlocks.contains(state.getBlock())) {
            return;
        }
        // checkInsideBlocks reuses one MutableBlockPos for the whole sweep.
        BlockPos immutable = pos.immutable();
        if (DeferredWork.park(() -> {
            // Replayed by the coordinator with nothing else running, which is
            // the one place a block's writes can be counted exactly. Effects the
            // block hands to the applier land in the entity's own collector and
            // are applied on its next tick, one tick late, same as the write.
            TickContext.Local local = TickContext.local();
            long before = local.worldWrites;
            state.entityInside(level, immutable, entity, applier, intersects);
            HazardWatch.observe(state.getBlock(), local.worldWrites != before);
        })) {
            ci.cancel();
        }
    }
}
