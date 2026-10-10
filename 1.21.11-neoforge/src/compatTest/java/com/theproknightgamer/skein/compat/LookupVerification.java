package com.theproknightgamer.skein.compat;

import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * The per-type index behind {@code EntityLookup.getEntities}: every typed walk
 * must return what filtering the full walk returns, in the same order, through
 * adds, removals, re-adds, a refused duplicate UUID and an early abort.
 */
final class LookupVerification {
    private LookupVerification() {}

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static <U extends Entity> List<Entity> typed(EntityLookup<Entity> lookup, EntityTypeTest<Entity, U> test, int limit) {
        List<Entity> out = new ArrayList<>();
        lookup.getEntities(test, entity -> {
            out.add(entity);
            return out.size() >= limit ? AbortableIterationConsumer.Continuation.ABORT : AbortableIterationConsumer.Continuation.CONTINUE;
        });
        return out;
    }

    private static List<Entity> reference(EntityLookup<Entity> lookup, EntityType<?> type, int limit) {
        List<Entity> out = new ArrayList<>();
        for (Entity entity : lookup.getAllEntities()) {
            if (entity.getType() == type && out.size() < limit) out.add(entity);
        }
        return out;
    }

    private static void compare(EntityLookup<Entity> lookup, Random random) {
        for (EntityType<?> type : List.of(EntityType.SNOWBALL, EntityType.MARKER, EntityType.COW, EntityType.PIG)) {
            int limit = random.nextInt(4) == 0 ? 1 + random.nextInt(5) : Integer.MAX_VALUE;
            check(typed(lookup, type, limit).equals(reference(lookup, type, limit)), "typed walk differs for " + type);
        }
        List<Entity> all = new ArrayList<>();
        lookup.getAllEntities().forEach(all::add);
        List<Entity> anyType = typed(lookup, EntityTypeTest.forClass(Entity.class), Integer.MAX_VALUE);
        check(anyType.equals(all), "class-based walk changed");
    }

    static void run(ServerLevel level) {
        boolean old = SkeinConfig.Live.enabled;
        try {
            Random random = new Random(52019);
            EntityLookup<Entity> lookup = new EntityLookup<>();
            List<Entity> live = new ArrayList<>();
            List<Entity> gone = new ArrayList<>();
            for (int step = 0; step < 6000; step++) {
                int action = random.nextInt(10);
                if (action < 5 || live.isEmpty()) {
                    Entity entity = switch (random.nextInt(3)) {
                        case 0 -> new Snowball(level, 0, 100, 0, new ItemStack(Items.SNOWBALL));
                        case 1 -> new Marker(EntityType.MARKER, level);
                        default -> new Cow(EntityType.COW, level);
                    };
                    lookup.add(entity);
                    live.add(entity);
                } else if (action < 8) {
                    Entity entity = live.remove(random.nextInt(live.size()));
                    lookup.remove(entity);
                    gone.add(entity);
                } else if (action < 9 && !gone.isEmpty()) {
                    // Re-adding a removed entity moves it to the end of both walks.
                    Entity entity = gone.remove(random.nextInt(gone.size()));
                    lookup.add(entity);
                    live.add(entity);
                } else {
                    // A second entity with a live UUID is refused and must not appear.
                    Entity twin = new Snowball(level, 0, 100, 0, new ItemStack(Items.SNOWBALL));
                    twin.setUUID(live.get(random.nextInt(live.size())).getUUID());
                    lookup.add(twin);
                }
                if (step % 50 == 0) compare(lookup, random);
            }
            compare(lookup, random);
            SkeinConfig.Live.enabled = false;
            compare(lookup, random);
            Skein.LOGGER.info("Lookup verification passed: typed walks match the full walk's order through 6000 adds, "
                    + "removals, re-adds, refused duplicate UUIDs and early aborts");
        } finally {
            SkeinConfig.Live.enabled = old;
        }
    }
}
