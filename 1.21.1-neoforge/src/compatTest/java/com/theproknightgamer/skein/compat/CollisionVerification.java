package com.theproknightgamer.skein.compat;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.Hooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Entity-collision lookups answered from the section lists ({@code entities.collisionIndex})
 * give vanilla's answer, in vanilla's order: a stack of primed TNT, boats, minecarts, shulkers
 * and items in one spot, every lookup made with the index on and off and compared.
 */
final class CollisionVerification {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    static void run(ServerLevel level) {
        check(Hooks.landed("skein$markCollisionQuery"), "the collision lookup marker did not land");
        // In the spawn chunk the other checks load, high above them: entities added to a chunk
        // that is not loaded yet are not visible to queries until it is.
        BlockPos base = new BlockPos(6, 120, 10);
        level.getChunk(0, 0);
        level.setChunkForced(0, 0, true);
        List<Entity> made = new ArrayList<>();
        List<Entity> askers = new ArrayList<>();
        try {
            for (int i = 0; i < 300; i++) {
                PrimedTnt tnt = new PrimedTnt(EntityType.TNT, level);
                tnt.setPos(base.getX() + .5, base.getY(), base.getZ() + .5);
                tnt.setNoGravity(true);
                tnt.setFuse(30000);
                add(level, tnt, made);
                if (i % 100 == 0) askers.add(tnt);
            }
            for (int i = 0; i < 3; i++) {
                Boat boat = new Boat(EntityType.BOAT, level);
                boat.setPos(base.getX() + .2 + i * .7, base.getY(), base.getZ() + .5);
                boat.setNoGravity(true);
                add(level, boat, made);
                askers.add(boat);
            }
            for (int i = 0; i < 2; i++) {
                Minecart cart = new Minecart(EntityType.MINECART, level);
                cart.setPos(base.getX() + .5, base.getY(), base.getZ() + .1 + i * .8);
                cart.setNoGravity(true);
                add(level, cart, made);
                askers.add(cart);
            }
            for (int i = 0; i < 2; i++) {
                Shulker shulker = new Shulker(EntityType.SHULKER, level);
                shulker.setPos(base.getX() + 1.5, base.getY(), base.getZ() + i);
                shulker.setNoAi(true);
                add(level, shulker, made);
            }
            for (int i = 0; i < 20; i++) {
                ItemEntity item = new ItemEntity(level, base.getX() + .5, base.getY() + .2, base.getZ() + .5,
                        new ItemStack(Items.STONE));
                item.setNoGravity(true);
                add(level, item, made);
                if (i == 0) askers.add(item);
            }
            askers.add(null);
            boolean saved = SkeinConfig.Live.collisionIndex;
            int compared = 0;
            int nonEmpty = 0;
            try {
                for (Entity asker : askers) {
                    AABB box = (asker == null ? new AABB(base) : asker.getBoundingBox()).inflate(2.5);
                    SkeinConfig.Live.collisionIndex = false;
                    List<VoxelShape> vanilla = level.getEntityCollisions(asker, box);
                    SkeinConfig.Live.collisionIndex = true;
                    List<VoxelShape> indexed = level.getEntityCollisions(asker, box);
                    check(vanilla.size() == indexed.size(), "collision lookup from " + asker + " found "
                            + indexed.size() + " shapes, vanilla " + vanilla.size());
                    for (int i = 0; i < vanilla.size(); i++) {
                        check(vanilla.get(i).bounds().equals(indexed.get(i).bounds()),
                                "collision lookup from " + asker + " differs at shape " + i);
                    }
                    compared++;
                    if (!vanilla.isEmpty()) nonEmpty++;
                }
            } finally {
                SkeinConfig.Live.collisionIndex = saved;
            }
            check(nonEmpty >= 3, "the fixture produced too few collisions to compare: " + nonEmpty);
            Skein.LOGGER.info("Collision verification passed: {} lookups (TNT in a 300-stack, boats, minecarts, an item, "
                    + "no source) matched vanilla shape for shape with the section lists on, {} of them non-empty",
                    compared, nonEmpty);
        } finally {
            made.forEach(Entity::discard);
        }
    }

    private static void add(ServerLevel level, Entity entity, List<Entity> made) {
        check(level.addFreshEntity(entity), "fixture entity rejected: " + entity);
        made.add(entity);
    }
}
