package com.theproknightgamer.skein.compat;

import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.ClusterPass;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.PartEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The cluster tier's exact projectile isolation, checked against vanilla's own
 * hit code: every snowball or egg it keeps must be unable to hit any outsider.
 */
final class IsolationVerification {
    private static final int X = -8192;
    private static final int Y = 120;
    private static final int Z = -64;
    private static final double ISOLATION = 8.0;
    private static final List<Entity> entities = new ArrayList<>();

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static <T extends Entity> T add(ServerLevel level, T entity, double x, double y, double z) {
        entity.setPos(x, y, z);
        entity.setNoGravity(true);
        check(level.addFreshEntity(entity), "isolation fixture rejected");
        entities.add(entity);
        return entity;
    }

    private static Entity projectile(ServerLevel level, int i, double x, double y, double z, Vec3 motion) {
        Entity entity = i % 5 == 4 ? new SubclassSnowball(level) : i % 2 == 0 ? new Snowball(level, x, y, z, new ItemStack(Items.SNOWBALL)) : new ThrownEgg(level, x, y, z, new ItemStack(Items.EGG));
        add(level, entity, x, y, z);
        entity.setDeltaMovement(motion);
        return entity;
    }

    /** Runs isolation and returns the projectiles it kept in the tier. */
    private static List<Entity> isolate(ClusterPass pass, List<Entity> projectiles, List<Entity> outsiders) {
        pass.candidates().clear();
        pass.others().clear();
        pass.candidates().addAll(projectiles);
        pass.others().addAll(outsiders);
        pass.isolate(chunk -> true, ISOLATION);
        List<Entity> kept = new ArrayList<>(pass.candidates());
        check(kept.size() + pass.others().size() == projectiles.size() + outsiders.size(), "isolation lost an entity");
        pass.candidates().clear();
        pass.others().clear();
        return kept;
    }

    /** Vanilla's hit search, restricted to outsiders and ignoring whether they are currently hittable. */
    private static Entity vanillaHit(Entity projectile, Set<Entity> outsiders) {
        HitResult hit = ProjectileUtil.getHitResultOnMoveVector(projectile, target -> outsiders.contains(target)
                || target instanceof PartEntity<?> part && outsiders.contains(part.getParent()));
        return hit instanceof EntityHitResult entityHit ? entityHit.getEntity() : null;
    }

    private static boolean sphere(Entity projectile, List<Entity> outsiders) {
        return sphere(projectile, outsiders, ISOLATION);
    }

    private static boolean sphere(Entity projectile, List<Entity> outsiders, double radius) {
        for (Entity outsider : outsiders) {
            if (Math.abs(outsider.getX() - projectile.getX()) <= radius
                    && Math.abs(outsider.getY() - projectile.getY()) <= radius
                    && Math.abs(outsider.getZ() - projectile.getZ()) <= radius) return true;
        }
        return false;
    }

    /** How far the widest outsider's box -- a multipart one's parts included -- reaches from its position. */
    private static double widest(List<Entity> outsiders) {
        double widest = 0;
        for (Entity outsider : outsiders) {
            List<AABB> boxes = new ArrayList<>(List.of(outsider.getBoundingBox()));
            if (outsider.isMultipartEntity()) {
                for (var part : outsider.getParts()) boxes.add(part.getBoundingBox());
            }
            for (AABB box : boxes) {
                widest = Math.max(widest, Math.max(Math.max(outsider.getX() - box.minX, box.maxX - outsider.getX()),
                        Math.max(Math.max(outsider.getY() - box.minY, box.maxY - outsider.getY()),
                                Math.max(outsider.getZ() - box.minZ, box.maxZ - outsider.getZ()))));
            }
        }
        return widest;
    }

    static void run(ServerLevel level) {
        boolean old = SkeinConfig.Live.exactProjectileIsolation;
        SkeinConfig.Live.exactProjectileIsolation = true;
        try {
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) level.getChunk((X >> 4) + dx, (Z >> 4) + dz);
            ClusterPass pass = new ClusterPass();

            // The reported farm: a one-block column, and a player-sized bystander
            // one glass block away. Nothing in the column can reach it.
            double cx = X + .5, cz = Z + .5;
            List<Entity> outsiders = new ArrayList<>();
            outsiders.add(add(level, new ArmorStand(EntityTypes.ARMOR_STAND, level), cx, Y, cz - 1.8));
            Random random = new Random(40213);
            List<Entity> column = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                Entity ball = new Snowball(level, cx, Y, cz, new ItemStack(Items.SNOWBALL));
                add(level, ball, cx, Y + random.nextDouble() * 1.5, cz);
                ball.setDeltaMovement(0, random.nextDouble() - .5, 0);
                column.add(ball);
            }
            check(isolate(pass, column, outsiders).size() == column.size(), "bystander beside a column demoted its snowballs");
            SkeinConfig.Live.exactProjectileIsolation = false;
            check(isolate(pass, column, outsiders).isEmpty(), "radius isolation no longer demotes a close bystander");
            SkeinConfig.Live.exactProjectileIsolation = true;
            for (Entity ball : column) ball.discard();

            // One aimed straight through the bystander must still be demoted.
            Entity aimed = projectile(level, 0, cx, Y + 1, cz, new Vec3(0, 0, -2.5));
            check(vanillaHit(aimed, Set.of(outsiders.getFirst())) != null, "aimed fixture misses its target");
            check(isolate(pass, List.of(aimed), outsiders).isEmpty(), "projectile that hits a bystander stayed clustered");
            aimed.discard();

            // A crowd of outsiders, including a boss whose part hangs outside its
            // own box, and projectiles flying every way through them.
            outsiders.add(add(level, new Cow(EntityTypes.COW, level), cx + 6, Y, cz + 3));
            EnderDragon dragon = add(level, new EnderDragon(EntityTypes.ENDER_DRAGON, level), cx + 30, Y, cz);
            dragon.getSubEntities()[0].setPos(cx + 12, Y + 1, cz - 6);
            outsiders.add(dragon);
            Set<Entity> outsiderSet = Collections.newSetFromMap(new IdentityHashMap<>());
            outsiderSet.addAll(outsiders);
            List<Entity> projectiles = new ArrayList<>();
            Vec3[] aims = { outsiders.get(0).getBoundingBox().getCenter(), outsiders.get(1).getBoundingBox().getCenter(),
                    dragon.getSubEntities()[0].getBoundingBox().getCenter() };
            for (int i = 0; i < 1500; i++) {
                if (i % 4 == 1) {
                    // Aimed near an outsider: plenty of hits, and misses by a hair.
                    Vec3 aim = aims[random.nextInt(aims.length)];
                    Vec3 from = aim.add(random.nextDouble() * 6 - 3, random.nextDouble() * 4 - 2, random.nextDouble() * 6 - 3);
                    Vec3 motion = aim.subtract(from).scale(.5 + random.nextDouble())
                            .add(random.nextDouble() - .5, random.nextDouble() - .5, random.nextDouble() - .5);
                    projectiles.add(projectile(level, i, from.x, from.y, from.z, motion));
                    continue;
                }
                double speed = i % 3 == 0 ? 3 : .6;
                projectiles.add(projectile(level, i, cx - 10 + random.nextDouble() * 32, Y - 3 + random.nextDouble() * 8,
                        cz - 12 + random.nextDouble() * 20, new Vec3((random.nextDouble() * 2 - 1) * speed,
                                (random.nextDouble() * 2 - 1) * speed, (random.nextDouble() * 2 - 1) * speed)));
            }
            Set<Entity> kept = Collections.newSetFromMap(new IdentityHashMap<>());
            kept.addAll(isolate(pass, projectiles, outsiders));
            int hitters = 0, keptNear = 0;
            for (Entity projectile : projectiles) {
                boolean exact = projectile.getClass() == Snowball.class || projectile.getClass() == ThrownEgg.class;
                Entity target = vanillaHit(projectile, outsiderSet);
                if (target != null) hitters++;
                if (!exact) {
                    check(kept.contains(projectile) == !sphere(projectile, outsiders, ISOLATION + widest(outsiders)),
                            "modded subclass left the radius test");
                    continue;
                }
                if (kept.contains(projectile)) {
                    check(target == null, "clustered projectile can hit outsider " + target);
                    if (sphere(projectile, outsiders)) keptNear++;
                }
            }
            check(hitters > 100 && keptNear > 100, "fixture exercises too few hits or near misses: " + hitters + "/" + keptNear);

            // An entity in a chunk that is loaded but not entity-ticking is never
            // in the tick list, so it is never an outsider -- but a snowball can
            // still hit it. A path that reaches such a chunk goes to the grid.
            long blind = ChunkPos.pack((X >> 4) + 1, Z >> 4);
            Entity crossing = projectile(level, 0, (X & ~15) + 15.5, Y, cz, new Vec3(1.0, 0, 0));
            Entity inside = projectile(level, 2, (X & ~15) + 4.5, Y, cz, new Vec3(0.2, 0, 0));
            pass.candidates().clear();
            pass.others().clear();
            pass.candidates().addAll(List.of(crossing, inside));
            pass.isolate(chunk -> chunk != blind, ISOLATION);
            check(pass.others().contains(crossing) && !pass.candidates().contains(crossing),
                    "projectile reaching a chunk that is not entity-ticking stayed clustered");
            check(pass.candidates().contains(inside), "projectile clear of a non-ticking chunk was demoted");

            // Something that merges with its own kind gets a reach from its
            // speed, and goes to the grid when that would be too wide.
            boolean itemsCluster = SkeinConfig.Live.clusterRadius(EntityTypes.ITEM) > 0;
            if (itemsCluster) {
                ItemEntity slow = add(level, new ItemEntity(level, cx, Y, cz, new ItemStack(Items.STONE)), cx, Y, cz);
                ItemEntity fast = add(level, new ItemEntity(level, cx + 3, Y, cz, new ItemStack(Items.STONE)), cx + 3, Y, cz);
                slow.setDeltaMovement(0.1, 0, 0);
                fast.setDeltaMovement(3.0, 0, 0);
                pass.candidates().clear();
                pass.others().clear();
                pass.candidates().addAll(List.of(slow, fast));
                pass.isolate(chunk -> true, ISOLATION);
                check(pass.candidates().contains(slow) && pass.others().contains(fast),
                        "a fast item was kept in the tier, or a slow one demoted");
            }
            pass.candidates().clear();
            pass.others().clear();
            Skein.LOGGER.info("Isolation verification passed: bystander beside a column kept 400 snowballs, "
                    + "{} projectiles that vanilla hits were demoted, {} near misses kept, multipart and subclass cases, "
                    + "non-ticking chunks{}", hitters, keptNear, itemsCluster ? ", fast items" : "");
        } finally {
            for (Entity entity : entities) entity.discard();
            entities.clear();
            SkeinConfig.Live.exactProjectileIsolation = old;
        }
    }

    /** A modded subclass: may change what it hits, so it keeps the radius test. */
    private static final class SubclassSnowball extends Snowball {
        SubclassSnowball(ServerLevel level) { super(level, X, Y, Z, new ItemStack(Items.SNOWBALL)); }
    }
}
