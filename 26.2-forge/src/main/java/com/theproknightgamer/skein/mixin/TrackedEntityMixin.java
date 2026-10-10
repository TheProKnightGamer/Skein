package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.TrackingPass;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Predicate;

/**
 * A tracked entity's sends are collected instead of made while its
 * {@code sendChanges} runs on the pool; see {@link TrackingPass}.
 *
 * <p>1.21.11 sends through the three {@code ServerEntity.Synchronizer} methods
 * rather than one broadcast, so each collected send remembers which one it
 * was, and is made through that same method at the entity's turn in the loop.
 */
@HookFor(Hooks.Feature.ENTITY_TRACKING)
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin implements TrackingPass.Tracked {

    @Shadow @Final ServerEntity serverEntity;
    @Shadow @Final Entity entity;
    @Shadow SectionPos lastSectionPos;

    @Shadow public abstract void sendToTrackingPlayers(Packet<? super ClientGamePacketListener> packet);

    @Shadow public abstract void sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener> packet);

    @Shadow public abstract void sendToTrackingPlayersFiltered(Packet<? super ClientGamePacketListener> packet,
                                                               Predicate<ServerPlayer> filter);

    /** A collected {@code sendToTrackingPlayersAndSelf}. */
    private record AndSelf(Packet<? super ClientGamePacketListener> packet) {}

    /** A collected {@code sendToTrackingPlayersFiltered}. */
    private record Filtered(Packet<? super ClientGamePacketListener> packet, Predicate<ServerPlayer> filter) {}

    @Override
    public Entity skein$entity() {
        return this.entity;
    }

    @Override
    public ServerEntity skein$serverEntity() {
        return this.serverEntity;
    }

    @Override
    public SectionPos skein$lastSectionPos() {
        return this.lastSectionPos;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void skein$sendNow(Object collected) {
        if (collected instanceof AndSelf send) {
            this.sendToTrackingPlayersAndSelf(send.packet());
        } else if (collected instanceof Filtered send) {
            this.sendToTrackingPlayersFiltered(send.packet(), send.filter());
        } else {
            this.sendToTrackingPlayers((Packet<? super ClientGamePacketListener>) collected);
        }
    }

    @Inject(method = "sendToTrackingPlayers", at = @At("HEAD"), cancellable = true)
    private void skein$collect(Packet<? super ClientGamePacketListener> packet, CallbackInfo ci) {
        List<Object> collector = TrackingPass.collector();
        if (collector != null) {
            collector.add(packet);
            ci.cancel();
        }
    }

    @Inject(method = "sendToTrackingPlayersAndSelf", at = @At("HEAD"), cancellable = true)
    private void skein$collectAndSelf(Packet<? super ClientGamePacketListener> packet, CallbackInfo ci) {
        List<Object> collector = TrackingPass.collector();
        if (collector != null) {
            collector.add(new AndSelf(packet));
            ci.cancel();
        }
    }

    @Inject(method = "sendToTrackingPlayersFiltered", at = @At("HEAD"), cancellable = true)
    private void skein$collectFiltered(Packet<? super ClientGamePacketListener> packet, Predicate<ServerPlayer> filter,
                                       CallbackInfo ci) {
        List<Object> collector = TrackingPass.collector();
        if (collector != null) {
            collector.add(new Filtered(packet, filter));
            ci.cancel();
        }
    }
}
