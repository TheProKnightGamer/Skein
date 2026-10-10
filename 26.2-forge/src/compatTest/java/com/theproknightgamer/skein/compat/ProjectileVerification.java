package com.theproknightgamer.skein.compat;

import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.DeferredBuffer;
import com.theproknightgamer.skein.core.ProjectileQueries;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/** Real transformed queries: compare the original predicate with the indexed path. */
final class ProjectileVerification {
    private static final int X = -4096;
    private static final int Y = 100;
    private static final int Z = -32;
    private static final Method CAN_HIT;
    private static final List<Entity> entities = new ArrayList<>();
    private static final AtomicInteger impacts = new AtomicInteger();
    private static boolean cancelImpact;

    static {
        try {
            CAN_HIT = Projectile.class.getDeclaredMethod("canHitEntity", Entity.class);
            CAN_HIT.setAccessible(true);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static Predicate<Entity> filter(Projectile source) {
        return entity -> {
            try { return (boolean) CAN_HIT.invoke(source, entity); }
            catch (Exception e) { throw new IllegalStateException(e); }
        };
    }

    private static <T extends Entity> T add(ServerLevel level, T entity, double x, double y, double z) {
        entity.setPos(x, y, z);
        check(level.addFreshEntity(entity), "projectile fixture rejected");
        entities.add(entity);
        return entity;
    }

    private static void scoped(ServerLevel level, Runnable action) {
        DeferredBuffer buffer = new DeferredBuffer();
        TickContext.State previous = TickContext.enter(level, true, buffer, true);
        try { action.run(); }
        finally { TickContext.exit(previous); buffer.drain(level); }
    }

    private static void compare(ServerLevel level, Snowball source, AABB box) {
        Predicate<Entity> normal = filter(source);
        Predicate<Entity> indexed = ProjectileQueries.mark(source, normal);
        check(indexed instanceof ProjectileQueries.Filter, "threaded query was not eligible");
        List<Entity> expected = level.getEntities(source, box, normal);
        check(expected.equals(level.getEntities(source, box, indexed)), "candidate membership/order changed");
        HitResult vanillaHit = ProjectileUtil.getHitResultOnMoveVector(source, normal);
        HitResult indexedHit = ProjectileUtil.getHitResultOnMoveVector(source, indexed);
        check(vanillaHit.getType() == indexedHit.getType() && vanillaHit.getLocation().equals(indexedHit.getLocation()),
                "projectile hit point/type changed");
        if (vanillaHit instanceof EntityHitResult vanillaEntity) {
            check(indexedHit instanceof EntityHitResult actual && actual.getEntity() == vanillaEntity.getEntity(),
                    "nearest/tied projectile target changed");
        }
    }

    static void run(ServerLevel level) {
        boolean old = SkeinConfig.Live.threadedProjectileQueries;
        SkeinConfig.Live.threadedProjectileQueries = true;
        try {
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) level.getChunk((X >> 4) + dx, (Z >> 4) + dz);
            Random random = new Random(81371);
            for (int i = 0; i < 512; i++) {
                Entity target = i % 2 == 0 ? new Snowball(level, X, Y, Z, new ItemStack(Items.SNOWBALL)) : new ThrownEgg(level, X, Y, Z, new ItemStack(Items.EGG));
                add(level, target, X + random.nextDouble() * 32 - 16, Y + random.nextDouble() * 3, Z + random.nextDouble() * 32 - 16);
                if (i % 32 == 0) add(level, new Cow(EntityTypes.COW, level), X + random.nextDouble() * 32 - 16, Y, Z + random.nextDouble() * 32 - 16);
            }
            Snowball source = new Snowball(level, X + .5, Y + .5, Z + .5, new ItemStack(Items.SNOWBALL));
            source.setDeltaMovement(new Vec3(12, 0, 0));
            AABB all = new AABB(X - 32, Y - 2, Z - 32, X + 32, Y + 8, Z + 32);
            Predicate<Entity> normal = filter(source);
            check(ProjectileQueries.mark(source, normal) == normal, "serial query was marked");
            scoped(level, () -> {
                for (int i = 0; i < 96; i++) {
                    source.setPos(X + random.nextDouble() * 16 - 8, Y + .5, Z + random.nextDouble() * 16 - 8);
                    source.setDeltaMovement(new Vec3(random.nextDouble() * 24 - 12, 0, random.nextDouble() * 24 - 12));
                    compare(level, source, all);
                }
                check(level.getEntities((Entity) null, all, e -> true).stream().anyMatch(e -> e.getClass() == Snowball.class),
                        "unrelated predicate lost snowballs");
                check(ProjectileQueries.mark(new SelectiveSnowball(level), e -> true).getClass() != ProjectileQueries.Filter.class,
                        "custom shooter predicate was marked");
                SkeinConfig.Live.threadedProjectileQueries = false;
                check(ProjectileQueries.mark(source, normal) == normal, "disabled optimization still marked queries");
                SkeinConfig.Live.threadedProjectileQueries = true;
            });

            // A subclass stays in the list even while currently unpickable.
            // Eligibility, movement, and removal must be read live afterward.
            SelectiveSnowball changing = add(level, new SelectiveSnowball(level), X + 4, Y + .5, Z + .5);
            source.setPos(X + .5, Y + .5, Z + .5);
            source.setDeltaMovement(new Vec3(12, 0, 0));
            scoped(level, () -> {
                compare(level, source, all);
                changing.pickable = true;
                changing.onPickable = () -> check(level.getEntities((Entity) null, all, e -> true).stream()
                        .anyMatch(e -> e.getClass() == Snowball.class), "nested unrelated query inherited target filtering");
                check(level.getEntities(source, all, ProjectileQueries.mark(source, normal)).contains(changing), "cached eligibility missed newly pickable target");
                compare(level, source, all);
                changing.onPickable = null;
                changing.setPos(X + 7, Y + .5, Z + .5);
                compare(level, source, all);
                changing.discard();
                compare(level, source, all);
                check(!level.getEntities(source, all, ProjectileQueries.mark(source, normal)).contains(changing), "query retained removed target");
            });
            SelectiveSnowball added = add(level, new SelectiveSnowball(level), X + 6, Y + .5, Z + .5);
            added.pickable = true;
            scoped(level, () -> {
                compare(level, source, all);
                check(level.getEntities(source, all, ProjectileQueries.mark(source, normal)).contains(added), "section addition did not invalidate target list");
            });
            added.setPos(X + 22, Y + .5, Z + .5); // Immediate section transfer between passes.
            scoped(level, () -> compare(level, source, all));

            Cow owner = add(level, new Cow(EntityTypes.COW, level), X + 2, Y, Z + .5);
            Boat boat = add(level, new Boat(EntityTypes.OAK_BOAT, level, () -> Items.OAK_BOAT), X + 2, Y, Z + .5);
            check(owner.startRiding(boat, true, false), "owner fixture could not ride");
            source.setOwner(owner);
            scoped(level, () -> {
                compare(level, source, all);
                check(!level.getEntities(source, all, ProjectileQueries.mark(source, normal)).contains(owner), "owner immunity changed");
            });
            source.setOwner(null);
            BlockPos wall = new BlockPos(X + 3, Y, Z);
            level.setBlock(wall, Blocks.STONE.defaultBlockState(), 3);
            scoped(level, () -> compare(level, source, all));
            level.setBlock(wall, Blocks.AIR.defaultBlockState(), 3);

            // Explicit ties and multipart targets, away from the random crowd.
            Snowball tieSource = new Snowball(level, X + 40.5, Y + .5, Z + .5, new ItemStack(Items.SNOWBALL));
            tieSource.setDeltaMovement(new Vec3(10, 0, 0));
            AABB tieBounds = tieSource.getBoundingBox().expandTowards(tieSource.getDeltaMovement()).inflate(1);
            SelectiveSnowball first = add(level, new SelectiveSnowball(level), X + 44, Y + .5, Z + .5);
            SelectiveSnowball second = add(level, new SelectiveSnowball(level), X + 44, Y + .5, Z + .5);
            first.pickable = second.pickable = true;
            scoped(level, () -> {
                compare(level, tieSource, tieBounds);
                HitResult hit = ProjectileUtil.getHitResultOnMoveVector(tieSource, ProjectileQueries.mark(tieSource, filter(tieSource)));
                check(hit instanceof EntityHitResult e && e.getEntity() == first, "equal-distance tie order changed");
                first.discard();
                compare(level, tieSource, tieBounds);
            });
            second.discard();
            EnderDragon dragon = add(level, new EnderDragon(EntityTypes.ENDER_DRAGON, level), X + 48, Y, Z + .5);
            dragon.getSubEntities()[0].setPos(X + 44, Y + .5, Z + .5);
            scoped(level, () -> {
                compare(level, tieSource, tieBounds);
                check(level.getEntities(tieSource, tieBounds, ProjectileQueries.mark(tieSource, filter(tieSource)))
                        .contains(dragon.getSubEntities()[0]), "multipart target missing");
            });
            dragon.discard();

            // Readers share the immutable section list, but perform their own
            // bounds/predicate/ray tests on actual Skein worker threads.
            DeferredBuffer[] buffers = new DeferredBuffer[64];
            for (int i = 0; i < buffers.length; i++) buffers[i] = new DeferredBuffer();
            Skein.pool().invokeAll(buffers.length, index -> {
                TickContext.State previous = TickContext.enter(level, true, buffers[index], true);
                try {
                    Snowball workerSource = new Snowball(level, X + .5, Y + .5, Z + .5, new ItemStack(Items.SNOWBALL));
                    workerSource.setDeltaMovement(new Vec3(12, 0, 0));
                    for (int i = 0; i < 8; i++) compare(level, workerSource, all);
                } finally { TickContext.exit(previous); }
            });
            for (DeferredBuffer buffer : buffers) buffer.drain(level);
            impactChecks(level);
            Skein.LOGGER.info("Projectile verification passed: ordered candidates and hit results, live eligibility, "
                    + "adds/removals/moves, owner immunity, custom predicate fallback, block clipping, "
                    + "ties and multipart targets, 512 concurrent comparisons, damage, and impact-event cancellation");
        } finally {
            for (Entity entity : entities) entity.discard();
            entities.clear();
            SkeinConfig.Live.threadedProjectileQueries = old;
        }
    }

    private static void impactChecks(ServerLevel level) {
        // Isolated from the random candidates above; exercise ThrowableProjectile.tick itself.
        int x = X + 40;
        BlockPos wall = new BlockPos(x + 3, Y, Z);
        NeoForge.EVENT_BUS.addListener(ProjectileImpactEvent.class, event -> {
            if (event.getProjectile().entityTags().contains("skein_projectile_impact")) {
                impacts.incrementAndGet();
                if (cancelImpact) event.setCanceled(true);
            }
        });
        try {
            for (boolean entityHit : new boolean[] {false, true}) for (boolean cancel : new boolean[] {false, true}) {
                level.setBlock(wall, entityHit ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState(), 3);
                cancelImpact = cancel;
                Object expected = null;
                for (boolean indexed : new boolean[] {false, true}) {
                    SkeinConfig.Live.threadedProjectileQueries = indexed;
                    Snowball ball = add(level, new Snowball(level, x + .5, Y + .5, Z + .5, new ItemStack(Items.SNOWBALL)), x + .5, Y + .5, Z + .5);
                    ball.addTag("skein_projectile_impact");
                    ball.setDeltaMovement(new Vec3(6, 0, 0));
                    Blaze blaze = entityHit ? add(level, new Blaze(EntityTypes.BLAZE, level), x + 3.5, Y, Z + .5) : null;
                    float health = blaze == null ? 0 : blaze.getHealth();
                    impacts.set(0);
                    scoped(level, () -> {
                        // What tickNonPassenger does first: 1.21.11 sweeps block effects from here.
                        ball.setOldPosAndRot();
                        ball.tick();
                    });
                    float damage = blaze == null ? 0 : health - blaze.getHealth();
                    List<Object> actual = List.of(ball.isRemoved(), ball.position(), ball.getDeltaMovement(), impacts.get(), damage);
                    // NeoForge 1.21.11 applies a cancelled impact to the projectile's
                    // position only; the hit itself still happens. So what a cancel
                    // does is vanilla's business here, and the check that matters is
                    // the one below: both query paths end the same way.
                    check(impacts.get() == 1, "impact event fired " + impacts.get() + " times");
                    check(!entityHit || cancel || damage == 3, "snowball damage changed: " + damage);
                    if (expected == null) expected = actual;
                    else check(expected.equals(actual), "real snowball tick differs from vanilla query path");
                    ball.discard();
                    if (blaze != null) blaze.discard();
                }
            }
        } finally {
            cancelImpact = false;
            SkeinConfig.Live.threadedProjectileQueries = true;
            level.setBlock(wall, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static final class SelectiveSnowball extends Snowball {
        boolean pickable;
        Runnable onPickable;
        SelectiveSnowball(ServerLevel level) { super(level, X, Y, Z, new ItemStack(Items.SNOWBALL)); }
        @Override public boolean isPickable() {
            if (onPickable != null) onPickable.run();
            return pickable;
        }
        @Override protected boolean canHitEntity(Entity entity) { return true; }
    }
}
