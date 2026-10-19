package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

/**
 * Points out blocks that are being parked for nothing.
 *
 * <p>Asking which class declares {@code entityInside} finds every block that
 * reacts to an entity at all, which is far more than the ones that write because
 * of it. A bubble column overrides it to push the entity and spawn two splash
 * particles -- one block read, no writes -- and treating that as hazardous parks
 * the single most frequent operation in a projectile farm onto the coordinator.
 * Profiling put it at a fifth of all server-thread time.
 *
 * <p>The tempting fix is to watch the replays and release a block that never
 * writes. That is wrong, and subtly so: {@code BasePressurePlateBlock} only
 * calls {@code setBlock} when the signal actually <em>changes</em>, so a plate
 * that is already held down looks inert for as long as anything stands on it,
 * would be released on that evidence, and would then race the instant it
 * depresses. Observation cannot prove the absence of a write; it can only fail
 * to have seen one yet.
 *
 * <p>So nothing here changes behaviour. It counts clean replays and, once a
 * block has gone long enough without writing, says so once in the log as a
 * candidate for {@code entities.cluster.inertBlocks} -- which is an audited
 * list, because deciding that a block never writes is a claim about its code
 * rather than about a sample of its behaviour.
 */
public final class HazardWatch {
    private HazardWatch() {}

    /** Clean replays before a block is worth mentioning. */
    private static final int SUGGEST_AFTER = 400;

    private static final int REPORTED = Integer.MIN_VALUE;

    /**
     * Written while a coordinator drains a {@link DeferredBuffer} at a
     * barrier. That is one thread per level, but with dimensions in parallel
     * it is several levels at once, so the two methods below share a lock.
     * Only replays of parked hazard contacts get here, so it is never hot.
     */
    private static final Reference2IntOpenHashMap<Block> CLEAN_RUNS = new Reference2IntOpenHashMap<>();

    /** Records what one replayed block reaction did. Never changes behaviour. */
    public static synchronized void observe(Block block, boolean wrote) {
        if (!wrote && !Hooks.landed("skein$countWorldWrite")) {
            // Writes are not being counted, so "wrote nothing" means nothing.
            return;
        }
        int seen = CLEAN_RUNS.getInt(block);
        if (seen == REPORTED) {
            return;
        }
        if (wrote) {
            // Writes at least sometimes, which is the whole question settled.
            CLEAN_RUNS.put(block, REPORTED);
            return;
        }
        if (++seen >= SUGGEST_AFTER) {
            CLEAN_RUNS.put(block, REPORTED);
            Skein.LOGGER.info("Skein: {} has reacted to {} entities without writing anything. If its "
                            + "entityInside, stepOn, fallOn and updateEntityMovementAfterFallOn genuinely cannot write, adding it to "
                            + "entities.cluster.inertBlocks stops it being parked at the barrier.",
                    BuiltInRegistries.BLOCK.getKey(block), SUGGEST_AFTER);
            return;
        }
        CLEAN_RUNS.put(block, seen);
    }

    /** Forgets everything learned, for a config reload or a new server. */
    public static synchronized void reset() {
        CLEAN_RUNS.clear();
    }
}
