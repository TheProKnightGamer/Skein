package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.SkeinVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes the flag a trade sets and the villager's next tick acts on, and
 * whether its trades are still to be rolled, so the entity split can keep
 * those ticks on the level thread. See
 * {@code EntityTicker#isPinned}.
 */
@Mixin(Villager.class)
public abstract class VillagerMixin extends AbstractVillager implements SkeinVillager {

    protected VillagerMixin(EntityType<? extends AbstractVillager> type, Level level) {
        super(type, level);
    }

    @Shadow private boolean increaseProfessionLevelOnUpdate;

    @Override
    public boolean skein$levelsUpThisTick() {
        // Also a villager past the first level with no trades yet -- summoned,
        // or loaded without them: its first getOffers() rolls the trades of its
        // level, a cartographer's explorer map among them.
        return this.increaseProfessionLevelOnUpdate
                || this.offers == null && ((Villager) (Object) this).getVillagerData().level() >= 2;
    }
}
