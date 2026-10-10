package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import net.minecraft.ReportedException;
import net.minecraft.CrashReport;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One piston push of a large stack of entities, spread over the pool.
 *
 * <p>A moving block pushes every entity in its path, one after another, inside
 * its own block-entity tick. A cannon's payload is a stack of tens of thousands
 * of primed TNT on one spot, and every push of it -- the duper's lane going back
 * and forth through the stacks, the walkers carrying them, the final merge --
 * is that many entity moves in one tick on one thread: about 90ms a tick by the
 * end of a 30,000-TNT build, all of it in a single block entity, which no
 * spatial split can share out.
 *
 * <p>Inside one push the entities are independent. How far each one moves is
 * worked out from its own box before it moves; its move reads blocks and writes
 * only itself; a primed TNT or a falling block cannot be collided with, so none
 * of them can stop another. The two writes a move can make beyond itself -- the
 * entity changing section, and a block it passes through reacting to it -- are
 * exactly what Skein already parks inside a pass. So the loop runs as vanilla
 * wrote it, except that each entity's {@code moveEntityByPiston} (and the
 * {@code fixEntityWithinPistonBase} that may follow it) is collected instead of
 * run; at the end of the loop the collected moves run on the pool in contiguous
 * slices, each with its own buffer, and the buffers are replayed on this thread
 * in entity order. Every entity ends where vanilla puts it, sections end up
 * holding the same entities in the same order, and block reactions happen in
 * the same order, at the end of the push instead of during it.
 *
 * <p>Only when every pushed entity is an exact vanilla primed TNT or falling
 * block, none rides or carries anything, the batch is large, every chunk around
 * it is loaded, and this thread owns the level outside any pass. Anything else
 * runs the loop as before.
 */
public final class PistonPushes {
    private PistonPushes() {}

    /** Below this a push is cheaper on one thread than spread out. */
    static final int MIN_BATCH = 256;
    private static final int MIN_SLICE = 64;

    private static final AtomicLong BATCHES = new AtomicLong();
    private static final AtomicLong MOVED = new AtomicLong();

    public static long batches() {
        return BATCHES.get();
    }

    public static long moved() {
        return MOVED.get();
    }

    /** The collected moves of one push; owners and steps are parallel lists, an owner's steps adjacent. */
    public static final class Batch {
        final ServerLevel level;
        final ArrayList<Entity> owners = new ArrayList<>();
        final ArrayList<Runnable> steps = new ArrayList<>();

        Batch(ServerLevel level) {
            this.level = level;
        }
    }

    /** Whether a push of exactly these entities may be spread out. */
    private static boolean batchable(Level level, List<Entity> entities) {
        if (entities.size() < MIN_BATCH || !SkeinConfig.Live.enabled || !SkeinConfig.Live.parallelPistonPushes
                || !(level instanceof ServerLevel serverLevel) || Skein.pool() == null
                || TickContext.deferring() || !TickContext.owns(level)) {
            return false;
        }
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < entities.size(); i++) {
            Entity entity = entities.get(i);
            Class<?> type = entity.getClass();
            if (type != PrimedTnt.class && type != FallingBlockEntity.class
                    || entity.isPassenger() || entity.isVehicle() || entity.isRemoved()) {
                return false;
            }
            int cx = entity.getBlockX() >> 4;
            int cz = entity.getBlockZ() >> 4;
            minX = Math.min(minX, cx);
            maxX = Math.max(maxX, cx);
            minZ = Math.min(minZ, cz);
            maxZ = Math.max(maxZ, cz);
        }
        // A worker may not load a chunk, so every chunk a move could read must be loaded.
        ChunkMap map = serverLevel.getChunkSource().chunkMap;
        int full = ChunkLevel.byStatus(ChunkStatus.FULL);
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                ChunkHolder holder = map.getVisibleChunkIfPresent(ChunkPos.pack(x, z));
                if (holder == null || holder.getTicketLevel() > full || holder.getChunkIfPresent(ChunkStatus.FULL) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Called with the push's entity list, before the loop over it. */
    public static void begin(Level level, List<Entity> entities) {
        TickContext.Local local = TickContext.local();
        local.pistonBatch = batchable(level, entities) ? new Batch((ServerLevel) level) : null;
    }

    /** Takes one call of the loop for the batch, or returns false to let it run now. */
    public static boolean collect(Entity owner, Runnable step) {
        TickContext.Local local = TickContext.local();
        if (!(local.pistonBatch instanceof Batch batch)) {
            return false;
        }
        batch.owners.add(owner);
        batch.steps.add(step);
        return true;
    }

    /** Called where the loop ends: runs what was collected. */
    public static void flush() {
        TickContext.Local local = TickContext.local();
        if (!(local.pistonBatch instanceof Batch batch)) {
            return;
        }
        local.pistonBatch = null;
        int count = batch.steps.size();
        if (count == 0) {
            return;
        }
        // Slice boundaries fall between owners, so one entity's move and fix stay together and in order.
        SkeinPool pool = Skein.pool();
        int slice = Math.max(MIN_SLICE, count / Math.max(1, pool.size() * 4));
        List<int[]> ranges = new ArrayList<>();
        int from = 0;
        while (from < count) {
            int to = Math.min(count, from + slice);
            while (to < count && batch.owners.get(to) == batch.owners.get(to - 1)) {
                to++;
            }
            ranges.add(new int[] {from, to});
            from = to;
        }
        DeferredBuffer[] buffers = new DeferredBuffer[ranges.size()];
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = new DeferredBuffer();
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        pool.invokeAll(ranges.size(), index -> run(batch, ranges.get(index), buffers[index], failure));
        for (DeferredBuffer buffer : buffers) {
            buffer.drain(batch.level);
        }
        BATCHES.incrementAndGet();
        MOVED.addAndGet(count);
        Throwable thrown = failure.get();
        if (thrown != null) {
            // Where the loop would have thrown; the rest of the push has already run.
            throw thrown instanceof RuntimeException runtime ? runtime
                    : new ReportedException(CrashReport.forThrowable(thrown, "Moving entities by piston (Skein)"));
        }
    }

    private static void run(Batch batch, int[] range, DeferredBuffer buffer, AtomicReference<Throwable> failure) {
        TickContext.Local local = TickContext.local();
        TickContext.State previous = TickContext.enter(batch.level, true, buffer, true);
        try {
            for (int i = range[0]; i < range[1]; i++) {
                Entity owner = batch.owners.get(i);
                local.ticking = owner;
                try {
                    batch.steps.get(i).run();
                } catch (Throwable t) {
                    if (SkeinRefusal.in(t)) {
                        TickContext.resetBrackets();
                        SkeinRefusal.swallowed(owner, t);
                        continue;
                    }
                    TickContext.resetBrackets();
                    failure.compareAndSet(null, t);
                }
            }
        } finally {
            local.ticking = null;
            TickContext.exit(previous);
        }
    }
}
