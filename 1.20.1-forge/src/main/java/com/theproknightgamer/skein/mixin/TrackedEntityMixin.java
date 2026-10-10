package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.TrackingPass;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * A tracked entity's broadcast is collected instead of sent while its
 * {@code sendChanges} runs on the pool; see {@link TrackingPass}.
 */
@HookFor(Hooks.Feature.ENTITY_TRACKING)
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin implements TrackingPass.Tracked {

    @Shadow @Final ServerEntity serverEntity;
    @Shadow @Final Entity entity;
    @Shadow SectionPos lastSectionPos;

    @Shadow public abstract void broadcast(Packet<?> packet);

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
    public void skein$broadcastNow(Packet<?> packet) {
        this.broadcast(packet);
    }

    @Inject(method = "broadcast", at = @At("HEAD"), cancellable = true)
    private void skein$collect(Packet<?> packet, CallbackInfo ci) {
        List<Object> collector = TrackingPass.collector();
        if (collector != null) {
            collector.add(packet);
            ci.cancel();
        }
    }
}
