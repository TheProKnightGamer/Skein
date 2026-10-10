package com.theproknightgamer.skein.compat;

import com.mojang.authlib.GameProfile;
import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.compat.mixin.ChunkMapInvoker;
import com.theproknightgamer.skein.core.TrackingPass;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Entity tracking on the pool against tracking on the level's thread, as a
 * client receives it.
 *
 * <p>Two identical groups of items, 32 blocks apart so they sit the same way
 * against chunk sections, move the same way every tick in front of one fake
 * player. The first group's {@code sendChanges} runs on the pool; the second is
 * kept on vanilla's path. Once the player has been sent both groups, every tick
 * each item must produce the same packets as its twin -- positions measured
 * from its own group -- and the packets of each tick must arrive one entity at
 * a time, in the order the tracker map iterates them.
 */
public final class TrackingVerification {
    private TrackingVerification() {}

    private static final int GROUP = 32;
    // 32 apart, and straddling chunk lines (-16 and 16) so some items change
    // section every tick, which keeps them on the live path in both groups.
    private static final double POOL_X = -19;
    private static final double LIVE_X = 13;
    private static final String LIVE_TAG = "skein_tracking_live";

    private static ServerPlayer player;
    private static BenchConnection connection;
    private static final List<ItemEntity> pool = new ArrayList<>();
    private static final List<ItemEntity> live = new ArrayList<>();
    private static final Map<Integer, Integer> slot = new HashMap<>();
    private static volatile boolean window;
    private static final List<int[]> received = new ArrayList<>();
    private static final List<String> receivedText = new ArrayList<>();
    private static int previousMin;
    private static long sentBefore;
    private static long unsentBefore;
    private static int comparedTicks;
    private static int comparedPackets;
    private static int orderedTicks;
    private static int tick;
    private static final java.util.Set<Integer> paired = new java.util.HashSet<>();
    private static boolean pairedAtStart;
    private static final Map<Integer, Integer> order = new HashMap<>();

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    static void start(MinecraftServer server, ServerLevel level) {
        previousMin = TrackingPass.minEntities;
        TrackingPass.minEntities = 1;
        TrackingPass.keepLive = entity -> entity.entityTags().contains(LIVE_TAG);
        sentBefore = TrackingPass.sentOnPool();
        unsentBefore = TrackingPass.unsent();
        GameProfile profile = new GameProfile(UUID.fromString("0d7b4f5e-5b3c-4f0e-9a51-7d3f3c1e2a90"), "SkeinTracker");
        ClientInformation info = new ClientInformation("en_us", 2, ChatVisiblity.FULL, true, 0x7F,
                HumanoidArm.RIGHT, false, true, net.minecraft.server.level.ParticleStatus.ALL);
        player = new ServerPlayer(server, level, profile, info);
        player.snapTo(new net.minecraft.world.phys.Vec3(.5, -60, .5), 0, 0);
        connection = new BenchConnection();
        connection.listener = TrackingVerification::record;
        server.getPlayerList().placeNewPlayer(connection, player, new CommonListenerCookie(profile, 0, info, false));
        for (int i = 0; i < GROUP; i++) {
            pool.add(spawn(level, false, i));
            live.add(spawn(level, true, i));
        }
    }

    private static ItemEntity spawn(ServerLevel level, boolean keptLive, int index) {
        ItemEntity item = new ItemEntity(level, 0, -40, 0, new ItemStack(Items.COBBLESTONE, 64));
        item.setNoGravity(true);
        item.setUnlimitedLifetime();
        item.setNeverPickUp();
        item.setYRot(0);
        if (keptLive) item.addTag(LIVE_TAG);
        place(item, keptLive, index);
        // Before it is added, which can already pair it with the player.
        slot.put(item.getId(), keptLive ? GROUP + index : index);
        check(level.addFreshEntity(item), "tracking fixture rejected");
        return item;
    }

    private static void place(ItemEntity item, boolean keptLive, int index) {
        // Tracking looks before entities move, so it is where the item is put
        // that it sees: every other tick four blocks on, over the chunk line
        // for half of them, so they change section and take the live path.
        double x = (keptLive ? LIVE_X : POOL_X) + (index % 4) * 1.5 + (tick & 1) * 4;
        double z = 2 + (index / 4) * 1.5;
        item.setPos(x, -40 + (index % 3), z);
        // Every other tick fast enough to carry some items over a chunk line.
        item.setDeltaMovement((tick & 1) == 0 ? 4 : .25, (tick % 3) * .1, 0);
        // Send movement every tick rather than every 20th, and new data now and then.
        item.needsSync = true;
        if ((index + tick) % 5 == 0) item.setItem(new ItemStack(Items.COBBLESTONE, 1 + tick % 64));
    }

    static void beforeTick() {
        if (player == null) return;
        connection.answer(player);
        // What the player's own tick would do: pair it with entities in chunks
        // it has now been sent.
        player.level().getChunkSource().move(player);
        tick++;
        pairedAtStart = paired.size() == 2 * GROUP;
        for (int i = 0; i < GROUP; i++) {
            place(pool.get(i), false, i);
            place(live.get(i), true, i);
        }
    }

    /** Opened and closed around {@code ChunkMap.tick} in the overworld. */
    public static void window(net.minecraft.server.level.ChunkMap chunkMap, boolean open) {
        window = open && player != null;
        if (!window) return;
        // Where each entity comes in the tracker map's iteration, as the walk found it.
        order.clear();
        int position = 0;
        for (Object tracked : ((ChunkMapInvoker) chunkMap).skeinTest$entityMap().values()) {
            order.put(((TrackingPass.Tracked) tracked).skein$entity().getId(), position++);
        }
    }

    private static void record(Packet<?> packet) {
        if (packet instanceof ClientboundAddEntityPacket add && slot.containsKey(add.getId())) paired.add(add.getId());
        if (!window || packet instanceof ClientboundBundlePacket) return;
        ServerLevel level = player.level();
        int id;
        String text;
        if (packet instanceof ClientboundMoveEntityPacket move) {
            Entity entity = move.getEntity(level);
            if (entity == null) return;
            id = entity.getId();
            text = "move " + move.getXa() + " " + move.getYa() + " " + move.getZa() + " " + move.getYRot() + " "
                    + move.getXRot() + " " + move.isOnGround() + " " + move.hasPosition() + " " + move.hasRotation();
        } else if (packet instanceof ClientboundSetEntityMotionPacket motion) {
            id = motion.id();
            text = "motion " + motion.movement();
        } else if (packet instanceof ClientboundEntityPositionSyncPacket sync) {
            id = sync.id();
            net.minecraft.world.phys.Vec3 at = sync.values().position();
            text = "sync " + (at.x - origin(id)) + " " + at.y + " " + at.z + " " + sync.values().deltaMovement()
                    + " " + sync.values().yRot() + " " + sync.values().xRot() + " " + sync.onGround();
        } else if (packet instanceof ClientboundSetEntityDataPacket data) {
            id = data.id();
            StringBuilder builder = new StringBuilder("data");
            for (SynchedEntityData.DataValue<?> value : data.packedItems()) {
                builder.append(' ').append(value.id()).append('=').append(value.value());
            }
            text = builder.toString();
        } else if (packet instanceof ClientboundRotateHeadPacket head) {
            Entity entity = head.getEntity(level);
            if (entity == null) return;
            id = entity.getId();
            text = "head " + head.getYHeadRot();
        } else if (packet instanceof ClientboundAddEntityPacket add) {
            id = add.getId();
            text = "add " + (add.getX() - origin(id)) + " " + add.getY() + " " + add.getZ();
        } else {
            return;
        }
        Integer at = slot.get(id);
        if (at == null) return;
        received.add(new int[] {at, id});
        receivedText.add(packet.getClass().getSimpleName() + " " + text);
    }

    private static double origin(int id) {
        Integer at = slot.get(id);
        return at == null ? 0 : at >= GROUP ? LIVE_X : POOL_X;
    }

    /** After each tick: the tick's packets, twin against twin and in tracker order. */
    static void afterTick(ServerLevel level) {
        if (player == null) return;
        try {
            if (received.isEmpty()) return;
            int last = -1;
            int lastId = -1;
            List<Integer> finished = new ArrayList<>();
            for (int[] entry : received) {
                int at = order.getOrDefault(entry[1], -1);
                check(at >= 0, "a packet for an untracked fixture entity");
                if (entry[1] != lastId) {
                    check(at > last && !finished.contains(entry[1]),
                            "tracking packets out of the tracker's order at tick " + tick);
                    if (lastId >= 0) finished.add(lastId);
                    last = at;
                    lastId = entry[1];
                }
            }
            orderedTicks++;
            // Twins, once the player had been sent both groups before the tick began.
            if (!pairedAtStart) return;
            List<List<String>> bySlot = new ArrayList<>();
            for (int i = 0; i < 2 * GROUP; i++) bySlot.add(new ArrayList<>());
            for (int i = 0; i < received.size(); i++) bySlot.get(received.get(i)[0]).add(receivedText.get(i));
            for (int i = 0; i < GROUP; i++) {
                check(bySlot.get(i).equals(bySlot.get(GROUP + i)), "item " + i + " at tick " + tick
                        + ": pool sent " + bySlot.get(i) + ", live sent " + bySlot.get(GROUP + i));
                comparedPackets += bySlot.get(i).size();
            }
            comparedTicks++;
        } finally {
            received.clear();
            receivedText.clear();
        }
    }

    static String verify(MinecraftServer server) {
        TrackingPass.minEntities = previousMin;
        TrackingPass.keepLive = null;
        window = false;
        long sent = TrackingPass.sentOnPool() - sentBefore;
        check(TrackingPass.unsent() == unsentBefore, "collected tracking packets were never sent");
        check(comparedTicks >= 40, "only " + comparedTicks + " ticks compared both groups; " + paired.size()
                + " of " + 2 * GROUP + " items paired, " + orderedTicks + " ticks with packets, " + tick
                + " ticks, player at " + player.position() + ", " + connection.packets + " packets, chunk sent "
                + player.connection.chunkSender.isPending(net.minecraft.world.level.ChunkPos.pack(-2, 0)) + "/"
                + player.getChunkTrackingView().contains(-2, 0));
        check(comparedPackets >= comparedTicks * GROUP / 2, "only " + comparedPackets + " packets compared");
        check(sent >= (long) comparedTicks * GROUP / 2, "only " + sent + " entities' packets came from the pool");
        for (ItemEntity item : pool) item.discard();
        for (ItemEntity item : live) item.discard();
        server.getPlayerList().remove(player);
        player = null;
        Skein.LOGGER.info("Tracking verification passed: over {} ticks, {} packets from the pool matched their twins' "
                + "from the level thread; {} ticks' packets arrived in tracker order; {} entities sent from the pool",
                comparedTicks, comparedPackets, orderedTicks, sent);
        return comparedTicks + " tracking ticks";
    }
}
