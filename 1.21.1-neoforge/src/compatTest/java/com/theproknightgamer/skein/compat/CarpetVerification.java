package com.theproknightgamer.skein.compat;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.ExplosionRays;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Blocks;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

/**
 * Carpet's {@code explosionNoBlockDamage} rule against Skein's explosion hooks.
 *
 * <p>Carpet redirects the ray loop's block reads to bedrock while the rule is
 * on, and Skein both casts that loop on the pool and wraps the same read. With
 * the rule off the pool must cast and agree with the serial loop; with it on,
 * nothing may break, as with carpet alone.
 */
final class CarpetVerification {
    private static final int X = -2040;
    private static final int Y = 70;
    private static final int Z = 8;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    static void run(ServerLevel level) {
        Field rule;
        try {
            rule = Class.forName("carpet.CarpetSettings").getField("explosionNoBlockDamage");
        } catch (ReflectiveOperationException absent) {
            return;
        }
        check(Hooks.allows(Hooks.Feature.EXPLOSION_RAYS), "carpet's block-read redirect switched the pool's rays off");
        boolean enabled = SkeinConfig.Live.enabled;
        TickContext.State context = TickContext.enter(level, false, null);
        try {
            boolean before = rule.getBoolean(null);
            try {
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    level.getChunk((X >> 4) + dx, (Z >> 4) + dz);
                }
                for (int x = -4; x <= 4; x++) for (int y = -4; y <= 4; y++) for (int z = -4; z <= 4; z++) {
                    level.setBlock(new BlockPos(X + x, Y + y, Z + z), Blocks.DIRT.defaultBlockState(), 2);
                }
                ExplosionRaysVerification.settle(level);

                rule.setBoolean(null, false);
                long casts = ExplosionRays.casts();
                Set<BlockPos> pooled = explode(level, true);
                check(ExplosionRays.casts() == casts + 1, "rays were not cast on the pool beside carpet");
                Set<BlockPos> serial = explode(level, false);
                check(pooled.size() > 20, "the test explosion broke only " + pooled.size() + " blocks");
                check(pooled.equals(serial), "pool-cast rays beside carpet differ from the serial loop");

                rule.setBoolean(null, true);
                casts = ExplosionRays.casts();
                Set<BlockPos> protectedSkein = explode(level, true);
                check(ExplosionRays.casts() == casts, "rays were cast on the pool while carpet's rule was on");
                Set<BlockPos> protectedCarpet = explode(level, false);
                check(protectedSkein.isEmpty() && protectedCarpet.isEmpty(),
                        "explosionNoBlockDamage let " + protectedSkein.size() + " blocks break with Skein, "
                                + protectedCarpet.size() + " without");
                Skein.LOGGER.info("Carpet verification passed: rays cast on the pool and matched the serial loop "
                        + "({} blocks) with explosionNoBlockDamage off; nothing broke with it on", pooled.size());
            } finally {
                rule.setBoolean(null, before);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        } finally {
            TickContext.exit(context);
            SkeinConfig.Live.enabled = enabled;
        }
    }

    /** The blocks one explosion would break, without breaking them. */
    private static Set<BlockPos> explode(ServerLevel level, boolean enabled) {
        SkeinConfig.Live.enabled = enabled;
        level.random.setSeed(4091L);
        Explosion explosion = new Explosion(level, null, null, null, X + .5, Y + .5, Z + .5, 4, false,
                Explosion.BlockInteraction.DESTROY, ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER,
                SoundEvents.GENERIC_EXPLODE);
        explosion.explode();
        return new HashSet<>(explosion.getToBlow());
    }
}
