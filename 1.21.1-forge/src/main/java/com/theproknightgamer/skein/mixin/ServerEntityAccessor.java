package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.TrackingPass;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** The passengers last sent, which decide whether {@code sendChanges} may teleport a player. */
@Mixin(ServerEntity.class)
public interface ServerEntityAccessor extends TrackingPass.Paired {

    @Override
    @Accessor("lastPassengers")
    List<Entity> skein$lastPassengers();
}
