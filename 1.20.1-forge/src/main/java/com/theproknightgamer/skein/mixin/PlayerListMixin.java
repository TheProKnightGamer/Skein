package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.theproknightgamer.skein.core.HookFor;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Every {@code ServerPlayer} looks up its stats and advancements in the player
 * list's {@code HashMap}s as it is constructed, adding an entry the first time.
 * That includes the fake players machines build from their own ticks --
 * deployers, miners, breakers -- in different dimensions or cells at once.
 *
 * <p>Optional: without it, the maps are shared as they were before.
 */
@HookFor({})
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Unique
    private final Object skein$lock = new Object();

    @WrapMethod(method = "getPlayerStats")
    private ServerStatsCounter skein$getPlayerStats(Player player, Operation<ServerStatsCounter> original) {
        synchronized (this.skein$lock) {
            return original.call(player);
        }
    }

    @WrapMethod(method = "getPlayerAdvancements")
    private PlayerAdvancements skein$getPlayerAdvancements(ServerPlayer player,
                                                           Operation<PlayerAdvancements> original) {
        synchronized (this.skein$lock) {
            return original.call(player);
        }
    }
}
