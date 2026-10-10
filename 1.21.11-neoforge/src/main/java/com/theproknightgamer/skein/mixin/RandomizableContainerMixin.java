package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.DeferredWork;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * An unopened loot container rolls its loot on the level thread.
 *
 * <p>The first read of a container that still has a loot table rolls it, and a
 * hopper's tick, a hopper minecart or an explosion breaking the chest can be
 * that first read on a worker. A shipwreck's or ocean ruin's table holds an
 * explorer map, which searches up to fifty chunks away for its treasure and
 * loads chunks doing it -- refused on a worker. Vanilla clears the table
 * before it fills the container, so the refusal left the chest empty for good.
 *
 * <p>On a worker the roll is parked for the end of the pass instead, and the
 * container reads as empty until then: a hopper finds nothing to take this
 * tick and takes it next tick. A chest broken in the meantime has already
 * dropped its (empty) contents, so the parked roll drops what it produced
 * where the chest stood, as vanilla would have.
 */
@HookFor(Hooks.Feature.IN_LEVEL)
@Mixin(RandomizableContainerBlockEntity.class)
public abstract class RandomizableContainerMixin {

    @Shadow protected ResourceKey<LootTable> lootTable;

    @Unique private boolean skein$rollParked;

    @WrapOperation(method = {"isEmpty", "getItem", "removeItem", "removeItemNoUpdate", "setItem"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/RandomizableContainerBlockEntity;unpackLootTable(Lnet/minecraft/world/entity/player/Player;)V"))
    private void skein$rollOnLevelThread(RandomizableContainerBlockEntity self, Player player,
                                         Operation<Void> original) {
        if (this.lootTable == null || !TickContext.deferring()) {
            original.call(self, player);
            return;
        }
        synchronized (this) {
            if (this.skein$rollParked) {
                return;
            }
            this.skein$rollParked = true;
        }
        boolean parked = DeferredWork.park(() -> {
            synchronized (this) {
                this.skein$rollParked = false;
            }
            if (this.lootTable == null) {
                return;
            }
            boolean broken = self.isRemoved();
            original.call(self, player);
            if (broken && self.getLevel() != null) {
                Containers.dropContents(self.getLevel(), self.getBlockPos(), self);
            }
        });
        if (!parked) {
            synchronized (this) {
                this.skein$rollParked = false;
            }
            original.call(self, player);
        }
    }
}
