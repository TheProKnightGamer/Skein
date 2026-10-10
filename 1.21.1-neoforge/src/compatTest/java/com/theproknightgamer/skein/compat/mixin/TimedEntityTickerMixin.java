package com.theproknightgamer.skein.compat.mixin;

import com.theproknightgamer.skein.compat.PhaseTimes;
import com.theproknightgamer.skein.compat.PhaseTimes.Part;
import com.theproknightgamer.skein.core.EntityTicker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

@Mixin(value = EntityTicker.class, remap = false)
abstract class TimedEntityTickerMixin {
    @Inject(method = "run", at = @At("HEAD"))
    private static void skeinTest$begin(ServerLevel level, EntityTickList list, Consumer<Entity> action, Runnable serial, CallbackInfo ci) {
        if (level.dimension() == Level.OVERWORLD) PhaseTimes.begin(Part.ENTITIES);
    }

    @Inject(method = "run", at = @At("RETURN"))
    private static void skeinTest$end(ServerLevel level, EntityTickList list, Consumer<Entity> action, Runnable serial, CallbackInfo ci) {
        if (level.dimension() == Level.OVERWORLD) PhaseTimes.end(Part.ENTITIES);
    }
}
