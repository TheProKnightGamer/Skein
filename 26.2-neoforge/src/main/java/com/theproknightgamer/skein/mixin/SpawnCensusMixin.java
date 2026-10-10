package com.theproknightgamer.skein.mixin;

import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.SkeinConfig;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The mob census, when nothing is going to spawn.
 *
 * <p>Every tick {@code tickChunks} walks every entity in the level to count
 * mobs per category and per chunk, and it does so before looking at whether
 * mob spawning is even on. With {@code doMobSpawning} off the only readers of
 * that count are the F3 screen's "SC:" line and {@code /debug} reports; the
 * spawner itself never runs. On a world with a few thousand projectiles that
 * walk was a tenth of the server thread, spent on a number nobody used.
 *
 * <p>So while the game rule is off the previous census is reused and retaken
 * once a second, which keeps the debug line current. The moment the rule is on
 * again the census is taken every tick, exactly as vanilla takes it, before the
 * first spawn attempt reads it.
 */
@HookFor({})
@Mixin(ServerChunkCache.class)
public abstract class SpawnCensusMixin {

    @Unique private static final long SKEIN$IDLE_CENSUS_TICKS = 20;

    @Shadow @Final ServerLevel level;

    @Unique private NaturalSpawner.SpawnState skein$idleCensus;
    @Unique private long skein$idleCensusTime;

    @WrapOperation(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/NaturalSpawner;createState(ILjava/lang/Iterable;Lnet/minecraft/world/level/NaturalSpawner$ChunkGetter;Lnet/minecraft/world/level/LocalMobCapCalculator;)Lnet/minecraft/world/level/NaturalSpawner$SpawnState;"))
    private NaturalSpawner.SpawnState skein$skipIdleCensus(int spawnableChunks, Iterable<Entity> entities,
                                                           NaturalSpawner.ChunkGetter chunks, LocalMobCapCalculator caps,
                                                           Operation<NaturalSpawner.SpawnState> original) {
        if (!SkeinConfig.Live.enabled || this.level.getGameRules().get(GameRules.SPAWN_MOBS)) {
            this.skein$idleCensus = null;
            return original.call(spawnableChunks, entities, chunks, caps);
        }
        long now = this.level.getGameTime();
        if (this.skein$idleCensus == null || now - this.skein$idleCensusTime >= SKEIN$IDLE_CENSUS_TICKS
                || now < this.skein$idleCensusTime) {
            this.skein$idleCensus = original.call(spawnableChunks, entities, chunks, caps);
            this.skein$idleCensusTime = now;
        }
        return this.skein$idleCensus;
    }
}
