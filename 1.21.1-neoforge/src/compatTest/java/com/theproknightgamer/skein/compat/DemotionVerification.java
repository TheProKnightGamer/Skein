package com.theproknightgamer.skein.compat;

import com.mojang.authlib.GameProfile;
import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.core.Demotions;
import com.theproknightgamer.skein.core.TickMetrics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A type that only fails inside a parallel pass ({@code -PdemotionTest}).
 *
 * <p>One armor stand and one furnace among several hundred, and every cactus's
 * random tick, throw whenever they tick inside a parallel pass, the way a
 * mod's shared map does when two threads reach it.
 * Each type must be sent back to the level thread after its first failure, the
 * probes must go on ticking there without failing again, and
 * {@code /skein unquarantine} must give all three another chance.
 */
public final class DemotionVerification {
    public static final String THROW_TAG = "skein_demotion_throw";
    /** The furnace that throws inside a parallel pass; read by the probe mixin. */
    public static volatile BlockPos probeFurnace;
    /** Ticks the probe furnace made outside a parallel pass. */
    public static volatile int furnaceSerialTicks;
    /** Whether cactus random ticks throw inside a pass; read by the probe mixin. */
    public static volatile boolean cactusProbe;
    /** Cactus random ticks that ran outside a parallel pass. */
    public static volatile int cactusSerialTicks;

    private static final int X = 2048;
    private static final int Z = 2048;
    private static final int STANDS = 300;
    private static final int FURNACES = 200;

    private static ArmorStand probeStand;
    private static final List<ArmorStand> stands = new ArrayList<>();
    private static int ticks;
    private static int demotedAt = -1;
    private static long failuresAtDemotion;
    private static int standTicksAtDemotion;
    private static int furnaceTicksAtDemotion;
    private static int cactusTicksAtDemotion;

    DemotionVerification() {
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, DemotionVerification::start);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, DemotionVerification::afterTick);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static void start(ServerStartedEvent event) {
        ServerLevel level = event.getServer().overworld();
        Demotions.clear();
        // Loaded well past the probes, so none of them is pinned for sitting
        // near unloaded terrain.
        for (int cx = -12; cx <= 12; cx++) for (int cz = -12; cz <= 12; cz++) {
            level.setChunkForced((X >> 4) + cx, (Z >> 4) + cz, true);
        }
        for (int i = 0; i < STANDS; i++) {
            ArmorStand stand = new ArmorStand(EntityType.ARMOR_STAND, level);
            // About 300 blocks across, so the split has several cells to run.
            stand.setPos(X - 150 + (i % 20) * 15 + .5, -60, Z - 150 + (i / 20) * 20 + .5);
            stand.setNoGravity(true);
            stand.setInvulnerable(true);
            check(level.addFreshEntity(stand), "armor stand rejected");
            stands.add(stand);
        }
        probeStand = stands.get(STANDS / 2);
        probeStand.addTag(THROW_TAG);
        for (int i = 0; i < FURNACES; i++) {
            BlockPos pos = new BlockPos(X - 143 + (i % 20) * 15, -60, Z - 150 + (i / 20) * 30);
            level.setBlock(pos, Blocks.FURNACE.defaultBlockState(), 3);
            if (i == FURNACES / 2) probeFurnace = pos;
        }
        // Random ticks only reach chunks with a player nearby before 1.21.5.
        level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(300, event.getServer());
        for (int x = -96; x <= 96; x += 2) for (int z = -96; z <= 96; z += 2) {
            level.setBlock(new BlockPos(X + x + 1, -61, Z + z + 1), Blocks.SAND.defaultBlockState(), 2 | 16);
            level.setBlock(new BlockPos(X + x + 1, -60, Z + z + 1), Blocks.CACTUS.defaultBlockState(), 2 | 16);
        }
        cactusProbe = true;
        GameProfile profile = new GameProfile(UUID.fromString("5b2c1f0e-7a3d-4e19-8c62-1d0f9e3a7b44"), "SkeinDemotion");
        ClientInformation info = new ClientInformation("en_us", 2, ChatVisiblity.FULL, true, 0x7F,
                HumanoidArm.RIGHT, false, true);
        ServerPlayer player = new ServerPlayer(event.getServer(), level, profile, info);
        player.moveTo(X + .5, -60, Z + .5, 0, 0);
        event.getServer().getPlayerList().placeNewPlayer(new BenchConnection(), player,
                new CommonListenerCookie(profile, 0, info, false));
        Skein.LOGGER.info("Demotion run: {} armor stands and {} furnaces, one of each throws inside a parallel pass, "
                + "and so does every cactus random tick", STANDS, FURNACES);
    }

    private static void afterTick(ServerTickEvent.Post event) {
        if (probeStand == null) return;
        ticks++;
        boolean demoted = Demotions.entity(EntityType.ARMOR_STAND) && Demotions.blockEntity(BlockEntityType.FURNACE)
                && Demotions.block(Blocks.CACTUS);
        if (demotedAt < 0) {
            check(ticks < 40, "the throwing types were not demoted within 40 ticks: " + Demotions.describe()
                    + ", entities parallel " + TickMetrics.of(TickMetrics.Phase.ENTITIES).parallel()
                    + ", block entities parallel/serial " + TickMetrics.of(TickMetrics.Phase.BLOCK_ENTITIES).parallel()
                    + "/" + TickMetrics.of(TickMetrics.Phase.BLOCK_ENTITIES).serial()
                    + ", probe furnace ticks outside a pass " + furnaceSerialTicks
                    + ", random ticks parallel " + TickMetrics.of(TickMetrics.Phase.RANDOM_TICKS).parallel());
            if (demoted) {
                demotedAt = ticks;
                failuresAtDemotion = TickMetrics.failureCount();
                standTicksAtDemotion = probeStand.tickCount;
                furnaceTicksAtDemotion = furnaceSerialTicks;
                cactusTicksAtDemotion = cactusSerialTicks;
            }
            return;
        }
        if (ticks < demotedAt + 40) return;
        check(TickMetrics.failureCount() == failuresAtDemotion,
                "failures went on after demotion: " + failuresAtDemotion + " -> " + TickMetrics.failureCount());
        check(probeStand.tickCount >= standTicksAtDemotion + 39,
                "the probe armor stand stopped ticking: " + standTicksAtDemotion + " -> " + probeStand.tickCount);
        check(furnaceSerialTicks >= furnaceTicksAtDemotion + 39,
                "the probe furnace stopped ticking: " + furnaceTicksAtDemotion + " -> " + furnaceSerialTicks);
        check(cactusSerialTicks > cactusTicksAtDemotion + 40,
                "demoted cactus random ticks did not run at the end of the pass: " + cactusTicksAtDemotion + " -> "
                        + cactusSerialTicks);
        List<String> described = Demotions.describe();
        check(described.contains("minecraft:armor_stand") && described.contains("minecraft:furnace")
                        && described.contains("minecraft:cactus (random ticks)"),
                "status does not list the demoted types: " + described);
        // Remembered for the next start: the file names all three, and a fresh
        // load of it demotes them again.
        Path remembered = Path.of("config", "skein-demoted.txt");
        String file;
        try {
            file = Files.readString(remembered);
        } catch (Exception e) {
            throw new AssertionError("demotions were not written to " + remembered, e);
        }
        check(file.contains("entity minecraft:armor_stand") && file.contains("block_entity minecraft:furnace")
                && file.contains("block minecraft:cactus"), "the demotions file does not name all three: " + file);
        Demotions.clear();
        check(!Files.exists(remembered), "clearing the demotions left the file behind");
        try {
            Files.writeString(remembered, file);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Demotions.load();
        check(Demotions.describe().equals(described), "a fresh load did not restore the demotions: " + Demotions.describe());
        MinecraftServer server = event.getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "skein unquarantine");
        check(Demotions.describe().isEmpty(), "unquarantine left demoted types behind: " + Demotions.describe());
        check(!Files.exists(remembered), "unquarantine left the demotions file behind");
        String details = "armor stand, furnace and cactus random ticks demoted after their first failure (tick "
                + demotedAt + "), then 40 ticks outside the parallel passes with no further failures ("
                + (cactusSerialTicks - cactusTicksAtDemotion) + " cactus random ticks run at the end of the pass); "
                + "remembered in config/skein-demoted.txt and restored by a fresh load; unquarantine cleared all "
                + "three and the file";
        try {
            Files.writeString(Path.of("result.txt"), "PASS: " + details + "\n");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Skein.LOGGER.info("Demotion verification PASS: {}", details);
        probeStand = null;
        cactusProbe = false;
        server.halt(false);
    }
}
