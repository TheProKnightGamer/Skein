package com.theproknightgamer.skein.compat;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.DimensionTicker;
import com.theproknightgamer.skein.core.DeferredBuffer;
import com.theproknightgamer.skein.core.SpeeDeeCompat;
import com.theproknightgamer.skein.core.TickContext;
import com.theproknightgamer.skein.core.TickMetrics;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/** Runs against transformed classes in a disposable dedicated server. */
@Mod("skein_speedee_test")
public final class SpeedeeVerification {
    private static final String OFEM = "com.theproknightgamer.speedeeentity.ofem.";
    private static final List<ItemEntity> moving = new ArrayList<>();
    private static Method collide;
    private static Field mapField;
    private static int ticks;
    private static int warmup;
    private static int[] firstTick;
    private static long parallelTicks;
    private static long gridTicks;
    private static long clusterTicks;
    private static boolean indexed;

    public SpeedeeVerification() {
        if (Boolean.getBoolean("skein.test.demotion")) {
            new DemotionVerification();
            return;
        }
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, SpeedeeVerification::start);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Pre.class, SpeedeeVerification::beforeTick);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, SpeedeeVerification::afterTick);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void start(ServerStartedEvent event) {
        try {
            MinecraftServer server = event.getServer();
            ServerLevel level = server.overworld();
            ChunkCacheVerification.run(level);
            ExplosionVerification.run(level);
            ExplosionRaysVerification.run(level);
            ProjectileVerification.run(level);
            IsolationVerification.run(level);
            ThreadIdentityVerification.run();
            LookupVerification.run(level);
            GovernorVerification.run(level);
            TrackingVerification.start(server, level);
            if (Boolean.getBoolean("skein.test.orbital")) {
                check(SkeinConfig.Live.useAllCpuThreads, "all-CPU config did not load");
                int expected = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
                check(Skein.pool().size() == expected, "all-CPU worker pool has the wrong size");
                Skein.LOGGER.info("All-CPU config verified: {} processors, {} workers, configured manual count {}",
                        Runtime.getRuntime().availableProcessors(), Skein.pool().size(), SkeinConfig.Live.threadCount);
            }
            if (!ModList.get().isLoaded("speedeeentity")) {
                check(!((Object) level instanceof SpeeDeeCompat.LevelAccess), "optional mixin loaded without SpeeDeeEntity");
                finish(server, "optional mod absent");
                return;
            }
            collide = Class.forName(OFEM + "OFEMUtil").getMethod("collide", Entity.class, Vec3.class);
            mapField = Class.forName(OFEM + "TickCache").getField("map");
            level.getChunk(0, 0);
            level.setChunkForced(0, 0, true);
            List<Entity> leftovers = new ArrayList<>();
            level.getAllEntities().forEach(e -> { if (e.entityTags().contains("skein_speedee_fixture")) leftovers.add(e); });
            leftovers.forEach(Entity::discard);
            cacheOwnership(level, server.getLevel(Level.NETHER));
            blockChanges(level);
            try {
                Class.forName(OFEM + "EntityColliders");
                indexed = true;
                entityIndex(level);
            } catch (ClassNotFoundException legacy) {
                indexed = false;
            }
            // Moving items invoke Entity.move. Four separated cells ensure the
            // grid runs in parallel even when the precedence graph declines.
            // 1.21.11 keeps an entity on the level thread while any chunk within
            // entities.loadedMarginChunks (3) of it is not FULL, so each cell is
            // forced together with everything that close to it.
            int margin = SkeinConfig.Live.loadedMarginChunks;
            for (int i = 0; i < 4; i++) {
                for (int dx = -margin; dx <= margin; dx++) for (int dz = -margin; dz <= margin; dz++) {
                    level.getChunk(4 + 16 * i + dx, 4 + dz);
                    level.setChunkForced(4 + 16 * i + dx, 4 + dz, true);
                }
            }
            for (int i = 0; i < 512; i++) {
                ItemEntity entity = new ItemEntity(level, stressX(i), stressY(i), 64,
                        new ItemStack(Items.COBBLESTONE, 64));
                entity.addTag("skein_speedee_fixture");
                entity.setNoGravity(true);
                entity.setUnlimitedLifetime();
                entity.setNeverPickUp();
                check(level.addFreshEntity(entity), "stress entity rejected");
                moving.add(entity);
            }
            Skein.LOGGER.info("SpeeDeeEntity checks: cache ownership and same-tick block changes passed (collider index: {}); waiting for all forced chunks to tick", indexed);
        } catch (Exception e) {
            throw new IllegalStateException("SpeeDeeEntity integration checks failed", e);
        }
    }

    private static Long2ByteOpenHashMap map(Object cache) throws Exception {
        return (Long2ByteOpenHashMap) mapField.get(cache);
    }

    private static double stressX(int i) { return 64 + (i / 128) * 256 + (i % 8); }
    private static double stressY(int i) { return 90 + ((i / 8) % 16) * 3; }

    private static void parallel(ServerLevel level, int count, IntConsumer action) {
        DeferredBuffer[] buffers = new DeferredBuffer[count];
        for (int i = 0; i < count; i++) buffers[i] = new DeferredBuffer();
        Skein.pool().invokeAll(count, i -> {
            TickContext.State previous = TickContext.enter(level, true, buffers[i]);
            try { action.accept(i); }
            finally { TickContext.exit(previous); }
        });
        for (DeferredBuffer buffer : buffers) buffer.drain(level);
    }

    private static void cacheOwnership(ServerLevel level, ServerLevel other) throws Exception {
        Object main = SpeeDeeCompat.cacheFor(level);
        check(main == SpeeDeeCompat.cacheFor(level), "same-thread cache was not reused");
        map(main).put(42, (byte) 1);
        Object elsewhere = SpeeDeeCompat.cacheFor(other);
        check(main != elsewhere && map(elsewhere).get(42) == 0, "cache leaked between dimensions");
        check(map(SpeeDeeCompat.cacheFor(level)).get(42) == 1, "dimension switch discarded independent cache");
        Set<Object> caches = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
        CyclicBarrier barrier = new CyclicBarrier(8);
        Skein.pool().invokeAll(8, worker -> {
            try {
                Object cache = SpeeDeeCompat.cacheFor(level);
                caches.add(cache);
                Long2ByteOpenHashMap values = map(cache);
                barrier.await(10, TimeUnit.SECONDS);
                values.clear();
                for (int i = 0; i < 16384; i++) values.put(i, (byte) (worker + 1));
                barrier.await(10, TimeUnit.SECONDS);
                for (int i = 0; i < 16384; i++) check(values.get(i) == worker + 1, "concurrent rehash corrupted cache");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        check(caches.size() == 8, "workers shared collision caches");
        long now = level.getGameTime();
        ((net.minecraft.world.level.storage.ServerLevelData) level.getLevelData()).setGameTime(now + 1);
        check(map(SpeeDeeCompat.cacheFor(level)).isEmpty(), "cache survived tick change");
        map(main).put(42, (byte) 1);
        ((net.minecraft.world.level.storage.ServerLevelData) level.getLevelData()).setGameTime(now);
        check(map(SpeeDeeCompat.cacheFor(level)).isEmpty(), "cache survived backwards time change");
    }

    private static ItemEntity probe(ServerLevel level) {
        ItemEntity entity = new ItemEntity(level, .5, 70, .5, new ItemStack(Items.STONE));
        entity.setNoGravity(true);
        return entity;
    }

    private static Vec3 collision(Entity entity, Vec3 delta) throws Exception {
        return (Vec3) collide.invoke(null, entity, delta);
    }

    private static void blockChanges(ServerLevel level) throws Exception {
        BlockPos wall = new BlockPos(4, 70, 0);
        level.setBlock(wall, Blocks.AIR.defaultBlockState(), 3);
        Vec3 delta = new Vec3(8, 0, 0);
        // Fill every thread's cache at the same position and tick.
        parallel(level, 64, i -> {
            try { check(delta.equals(collision(probe(level), delta)), "empty sweep blocked"); }
            catch (Exception e) { throw new IllegalStateException(e); }
        });
        long tick = level.getGameTime();
        level.setBlock(wall, Blocks.STONE.defaultBlockState(), 3);
        parallel(level, 64, i -> {
            try {
                Vec3 result = collision(probe(level), delta);
                check(result != null && result.x > 0 && result.x < 4, "cached CLEAR crossed a new block");
            } catch (Exception e) { throw new IllegalStateException(e); }
        });
        // Direct chunk writes must also invalidate; game time is still frozen.
        level.getChunkAt(wall).setBlockState(wall, Blocks.AIR.defaultBlockState(), 0);
        check(delta.equals(collision(probe(level), delta)), "direct chunk removal left stale clearance");
        level.getChunkAt(wall).setBlockState(wall, Blocks.STONE.defaultBlockState(), 0);
        check(collision(probe(level), delta).x < 4, "direct chunk placement kept cached CLEAR");
        level.setBlock(wall, Blocks.AIR.defaultBlockState(), 3);
        check(level.getGameTime() == tick, "same-tick fixture advanced time");
    }

    private static void entityIndex(ServerLevel level) throws Exception {
        Class<?> type = Class.forName(OFEM + "TickCache");
        Method shapes = Class.forName(OFEM + "EntityColliders").getMethod("shapes", Level.class, type, Entity.class, AABB.class);
        Object shared = Class.forName(OFEM + "CacheHolder").getMethod("speedee$tickCache").invoke(level);
        AABB query = new AABB(0, 69, 0, 8, 73, 2);
        check(((List<?>) shapes.invoke(null, level, shared, probe(level), query)).isEmpty(), "fixture already has colliders");
        Boat boat = new Boat(EntityTypes.OAK_BOAT, level, () -> Items.OAK_BOAT);
        boat.addTag("skein_speedee_fixture");
        boat.setPos(4, 70, .5);
        boat.setNoGravity(true);
        check(level.addFreshEntity(boat), "boat rejected");
        parallel(level, 64, i -> {
            try {
                ItemEntity entity = probe(level);
                List<?> result = (List<?>) shapes.invoke(null, level, shared, entity, query);
                check(result.size() == level.getEntityCollisions(entity, query).size() && result.size() == 1,
                        "index missed same-tick collider addition");
            } catch (Exception e) { throw new IllegalStateException(e); }
        });
        check(((List<?>) type.getField("colliders").get(shared)).isEmpty(), "query mutated the shared collider list");
        boat.discard();
        check(((List<?>) shapes.invoke(null, level, shared, probe(level), query)).isEmpty(), "removed collider survived live filtering");
    }

    private static void beforeTick(ServerTickEvent.Pre event) {
        if (moving.isEmpty()) return;
        TrackingVerification.beforeTick();
        // Exercise both schedulers, and both collision paths in each scheduler.
        SkeinConfig.Live.clusterEntities = ticks < 50;
        for (int i = 0; i < moving.size(); i++) {
            ItemEntity entity = moving.get(i);
            entity.setPos(stressX(i), stressY(i), 64);
            // Alternate the real fast and vanilla slow movement paths.
            entity.setDeltaMovement((ticks & 1) == 0 ? 4 : .25, 0, 0);
        }
    }

    private static void afterTick(ServerTickEvent.Post event) {
        if (moving.isEmpty()) return;
        TrackingVerification.afterTick(event.getServer().overworld());
        check(TickMetrics.failureCount() == 0 && DimensionTicker.quarantined().isEmpty(), "parallel ticking failed");
        if (firstTick == null) {
            check(++warmup <= 400, "forced chunks did not begin ticking");
            if (moving.stream().anyMatch(entity -> entity.tickCount == 0)) return;
            firstTick = moving.stream().mapToInt(entity -> entity.tickCount).toArray();
            return;
        }
        boolean grid = TickMetrics.of(TickMetrics.Phase.ENTITIES).parallel() > 0;
        boolean cluster = TickMetrics.clusterTasks() > 1;
        if (grid) gridTicks++;
        if (cluster) clusterTicks++;
        if (grid || cluster) parallelTicks++;
        if (ticks % 25 == 0) {
            Skein.LOGGER.info("SpeeDee stress frame {}: {} parallel entities, {} cluster entities, item tickCount {}, position {}",
                    ticks, TickMetrics.of(TickMetrics.Phase.ENTITIES).parallel(), TickMetrics.clusterEntities(),
                    moving.getFirst().tickCount, moving.getFirst().position());
        }
        if (++ticks < 100) return;
        check(gridTicks >= 45 && clusterTicks >= 40, "stress fixture did not exercise both parallel schedulers");
        for (int i = 0; i < moving.size(); i++) {
            ItemEntity entity = moving.get(i);
            check(!entity.isRemoved(), "stress entity disappeared");
            check(entity.tickCount - firstTick[i] == ticks, "stress entity skipped ticks");
            entity.discard();
        }
        moving.clear();
        String scanned = TrackingVerification.verify(event.getServer());
        finish(event.getServer(), (indexed ? "indexed" : "legacy") + " cache; concurrent rehash; tick/dimension/block invalidation; "
                + parallelTicks + " parallel ticks (" + clusterTicks + " cluster, " + gridTicks + " grid) with 512 moving entities; "
                + scanned);
    }

    private static void finish(MinecraftServer server, String details) {
        try {
            Files.writeString(Path.of("result.txt"), "PASS: " + details + "\n");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Skein.LOGGER.info("SpeeDeeEntity verification PASS: {}", details);
        server.halt(false);
    }
}
