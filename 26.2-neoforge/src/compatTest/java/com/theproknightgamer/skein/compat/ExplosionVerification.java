package com.theproknightgamer.skein.compat;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.DeferredBuffer;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ServerExplosion;
import com.theproknightgamer.skein.compat.mixin.ServerExplosionInvoker;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import java.util.List;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Compares the transformed explosion with its disabled, vanilla path. */
final class ExplosionVerification {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    static void run(ServerLevel level) {
        boolean enabled = SkeinConfig.Live.enabled;
        TickContext.State context = TickContext.enter(level, false, null);
        try {
            BlockState[] states = { Blocks.AIR.defaultBlockState(), Blocks.GLASS.defaultBlockState(),
                    Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                    Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true),
                    Blocks.BEDROCK.defaultBlockState() };
            for (int x = -5; x <= 5; x++) for (int y = -5; y <= 5; y++) for (int z = -5; z <= 5; z++) {
                level.setBlock(new BlockPos(-1024 + x, 70 + y, z),
                        states[Math.floorMod(x * 31 + y * 17 + z * 7, states.length)], 2);
            }
            ExplosionRaysVerification.settle(level);
            int hurt = 0;
            for (int trial = 0; trial < 32; trial++) {
                Result vanilla = explode(level, trial, false);
                Result optimized = explode(level, trial, true);
                check(vanilla.equals(optimized), "explosion changed affected blocks, callbacks, RNG, damage or knockback: " + trial);
                if (optimized.hurts() > 0) hurt++;
            }
            check(hurt >= 8, "the explosion probe was hurt in only " + hurt + " trials; damage and knockback went untested");
            removedQueries(level);
            Skein.LOGGER.info("Explosion verification passed: 32 seeded vanilla comparisons, fluids, waterlogged blocks, "
                    + "custom resistance callbacks, direct chunk mutations, damage/knockback, and deferred-removal queries");
        } finally {
            TickContext.exit(context);
            SkeinConfig.Live.enabled = enabled;
        }
    }

    private record Result(Set<BlockPos> blocks, long calls, long nextRandom, float damage, int hurts, Vec3 motion) {}

    private static Result explode(ServerLevel level, int trial, boolean enabled) {
        SkeinConfig.Live.enabled = enabled;
        BlockPos mutable = new BlockPos(-1023, 70, 0);
        BlockState previous = level.getBlockState(mutable);
        // Include empty sky and the lower world boundary in addition to mixed
        // terrain, using exactly the same RNG stream in both implementations.
        double y = trial % 4 == 0 ? 100 : trial % 4 == 1 ? level.getMinY() + .25 : 70.5;
        Probe probe = new Probe(level);
        probe.setPos(-1022.5, y, .5);
        check(level.addFreshEntity(probe), "explosion probe rejected");
        long[] calls = {1};
        ExplosionDamageCalculator calculator = new ExplosionDamageCalculator() {
            private int visits;
            @Override public Optional<Float> getBlockExplosionResistance(Explosion explosion, BlockGetter reader,
                    BlockPos pos, BlockState state, FluidState fluid) {
                check(fluid == level.getFluidState(pos), "fluid read differs from level");
                calls[0] = calls[0] * 31 + pos.asLong();
                calls[0] = calls[0] * 31 + state.hashCode();
                calls[0] = calls[0] * 31 + fluid.hashCode();
                if (++visits == 20) level.getChunkAt(mutable).setBlockState(mutable, Blocks.WATER.defaultBlockState(), 0);
                return super.getBlockExplosionResistance(explosion, reader, pos, state, fluid);
            }
            @Override public boolean shouldBlockExplode(Explosion explosion, BlockGetter reader,
                    BlockPos pos, BlockState state, float power) {
                calls[0] = calls[0] * 31 + Float.floatToIntBits(power);
                return pos.getX() % 3 != 0;
            }
        };
        try {
            level.getRandom().setSeed(731L + trial);
            ServerExplosion explosion = new ServerExplosion(level, null, null, calculator, new Vec3(-1024.5, y, .5),
                    2 + trial % 5, false, Explosion.BlockInteraction.DESTROY);
            // Rays and entities, no breaking: both runs see the same terrain.
            ServerExplosionInvoker steps = (ServerExplosionInvoker) explosion;
            List<BlockPos> blocks = steps.skeinTest$calculateExplodedPositions();
            steps.skeinTest$hurtEntities(blocks);
            return new Result(new HashSet<>(blocks), calls[0], level.getRandom().nextLong(),
                    probe.damage, probe.hurts, probe.getDeltaMovement());
        } finally {
            probe.discard();
            level.getChunkAt(mutable).setBlockState(mutable, previous, 0);
        }
    }

    private static void removedQueries(ServerLevel level) {
        PrimedTnt removed = new PrimedTnt(level, -1024.5, 100, .5, null);
        check(level.addFreshEntity(removed), "removal probe rejected");
        PrimedTnt live = new PrimedTnt(level, -1024.25, 100, .5, null);
        check(level.addFreshEntity(live), "live query probe rejected");
        AABB box = removed.getBoundingBox().inflate(1);
        check(level.getEntities((Entity) null, box).contains(removed), "probe not indexed before removal");
        check(level.getEntitiesOfClass(PrimedTnt.class, box).contains(live), "typed query lost live probe");
        DeferredBuffer buffer = new DeferredBuffer();
        TickContext.State previous = TickContext.enter(level, true, buffer);
        try {
            removed.discard();
            check(!level.getEntities((Entity) null, box).contains(removed), "untyped query sees deferred removal");
            check(!level.getEntitiesOfClass(PrimedTnt.class, box).contains(removed), "typed query sees deferred removal");
            check(level.getEntities((Entity) null, box).contains(live), "untyped query filtered a live entity");
            check(level.getEntitiesOfClass(PrimedTnt.class, box).contains(live), "typed query filtered a live entity");
        } finally {
            TickContext.exit(previous);
            buffer.drain(level);
            live.discard();
        }
        check(level.getEntity(removed.getId()) == null, "barrier did not finish removal");
    }

    /** Not living, so its exposure can come from the pool; 1.21.11 makes TNT's hurt final. */
    private static final class Probe extends Snowball {
        float damage;
        int hurts;
        Probe(ServerLevel level) {
            super(level, -1022.5, 100, .5, new ItemStack(Items.SNOWBALL));
            setDeltaMovement(Vec3.ZERO);
        }
        @Override public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
            damage = amount;
            hurts++;
            return false;
        }
    }
}
