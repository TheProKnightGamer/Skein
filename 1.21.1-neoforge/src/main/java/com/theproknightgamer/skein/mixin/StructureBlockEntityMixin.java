package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.DeferredWork;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The one piece of vanilla whose reach is decided by a file on disk.
 *
 * <p>A structure block in LOAD mode writes a whole template into the world --
 * blocks, block entities and entities -- at an offset of its own choosing, and
 * both the offset and the template's size are as large as the file says.
 * Nothing about that fits inside the margin, and in SAVE mode it reads the same
 * region while other cells are writing it.
 *
 * <p>It cannot be kept out of a pass by type, either. A structure block has no
 * tick of its own: it fires from {@code neighborChanged}, so it is a redstone
 * edge raised by some other piece of work that reaches it, and the block being
 * ticked is never the block that does the writing. The scheduled-tick blacklist
 * looks at the ticks collected for a tick and cannot see this coming, and three
 * of the four phases have no such list at all.
 *
 * <p>So the placement is parked instead, the same way the navigation sweep is:
 * at the barrier {@code deferring()} is false, and the whole vanilla body runs
 * on the coordinator with nothing else in the level.
 */
@HookFor(Hooks.Feature.IN_LEVEL)
@Mixin(StructureBlockEntity.class)
public abstract class StructureBlockEntityMixin {

    @Inject(method = "placeStructure(Lnet/minecraft/server/level/ServerLevel;)V",
            at = @At("HEAD"), cancellable = true)
    private void skein$deferPlacement(ServerLevel level, CallbackInfo ci) {
        if (TickContext.deferring()) {
            StructureBlockEntity self = (StructureBlockEntity) (Object) this;
            DeferredWork.defer(level, () -> self.placeStructure(level));
            ci.cancel();
        }
    }

    /**
     * The read half. {@code fillFromWorld} walks the same region a placement
     * would write, and the template it fills comes from the level's own
     * manager, so a save taken mid-pass records a world that is halfway through
     * being changed. The caller is told it succeeded; it runs at the barrier.
     */
    @Inject(method = "saveStructure(Z)Z", at = @At("HEAD"), cancellable = true)
    private void skein$deferSave(boolean writeToDisk, CallbackInfoReturnable<Boolean> cir) {
        if (TickContext.deferring()) {
            StructureBlockEntity self = (StructureBlockEntity) (Object) this;
            DeferredWork.defer(self.getLevel(), () -> self.saveStructure(writeToDisk));
            cir.setReturnValue(true);
        }
    }
}
