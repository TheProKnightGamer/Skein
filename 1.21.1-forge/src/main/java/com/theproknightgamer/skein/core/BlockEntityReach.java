package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.SkeinConfig;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * How far a block entity's server tick can read or write, for the types that
 * reach less than the margin.
 *
 * <p>Ticking block entities in vanilla's order needs only the ones that could
 * affect each other kept together, and for most of them that is very close. A
 * hopper reads the container above it and the item entities over it, and
 * writes the container it faces -- a double chest's other half is two blocks
 * out -- whose comparators are two further. A furnace, a brewing stand or a
 * crafter sets its own block and tells the comparators beside it. Their
 * neighbour updates change no signal, so nothing cascades; whatever schedules
 * a tick in response is parked.
 *
 * <p>Everything not listed reaches the margin: a moving piston places a block
 * that can set off a wire, a daylight detector or a sculk sensor powers one, a
 * spawner puts mobs around itself, and a modded block entity could do
 * anything.
 *
 * <p>The block entities that always tick on the level's own thread
 * ({@code blockEntities.blacklist}) are there for what they do to players and
 * raiders tens of blocks away, which no other block entity touches. What they
 * do to blocks and the mobs beside them is close: a beacon reads its pyramid, a
 * conduit its frame and the monsters it attacks, an end gateway what stands in
 * it, and a bell nothing at all. Grouping them by that keeps a village's bell
 * from pulling every furnace for eighty blocks onto the level's thread with it.
 */
final class BlockEntityReach {
    private BlockEntityReach() {}

    private static volatile Map<BlockEntityType<?>, Integer> table;

    static int of(TickingBlockEntity ticker) {
        BlockEntity blockEntity = ticker instanceof SkeinTicker skeinTicker ? skeinTicker.skein$blockEntity() : null;
        if (blockEntity == null) {
            return SkeinConfig.Live.borderMargin;
        }
        Integer reach = table().get(blockEntity.getType());
        return reach != null ? reach : SkeinConfig.Live.borderMargin;
    }

    private static Map<BlockEntityType<?>, Integer> table() {
        Map<BlockEntityType<?>, Integer> map = table;
        if (map == null) {
            map = new IdentityHashMap<>();
            map.put(BlockEntityType.HOPPER, 4);
            map.put(BlockEntityType.FURNACE, 2);
            map.put(BlockEntityType.BLAST_FURNACE, 2);
            map.put(BlockEntityType.SMOKER, 2);
            map.put(BlockEntityType.BREWING_STAND, 2);
            map.put(BlockEntityType.CAMPFIRE, 2);
            map.put(BlockEntityType.BEEHIVE, 3);
            map.put(BlockEntityType.SHULKER_BOX, 2);
            map.put(BlockEntityType.CRAFTER, 2);
            map.put(BlockEntityType.ENCHANTING_TABLE, 3);
            map.put(BlockEntityType.SKULL, 1);
            map.put(BlockEntityType.SIGN, 1);
            map.put(BlockEntityType.HANGING_SIGN, 1);
            map.put(BlockEntityType.BEACON, 4);
            map.put(BlockEntityType.CONDUIT, 8);
            map.put(BlockEntityType.END_GATEWAY, 2);
            map.put(BlockEntityType.BELL, 1);
            table = map;
        }
        return map;
    }
}
