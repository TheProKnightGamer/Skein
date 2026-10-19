package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.util.Util;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Runs a {@link Partition} through its passes: the grid's four, then the wide
 * grid's four.
 *
 * <p>One barrier per pass, the writes each pass parked applied in cell order
 * before the next pass opens, and whatever could not be placed run serially at
 * the end on the thread that owns the level. Every phase Skein parallelises --
 * entities, random-ticked chunks, block entities -- is this loop over a
 * different pile.
 *
 * <p>Flushing between passes rather than once at the end is not just tidiness.
 * A pass may spawn entities, schedule ticks or move sections, and the next pass
 * has to see a registry that is whole rather than one that is halfway through
 * being updated.
 */
public final class ParallelPass {
    private ParallelPass() {}

    /** Below this a pass is cheaper to fold into the serial tail than to open a barrier for. */
    private static final int MIN_CELLS_PER_PASS = 2;
    private static final int MIN_ITEMS_PER_PASS = 24;
    /**
     * The same for a pass of the wide grid. What lands there is a mob with a long
     * follow range or something larger -- a villager's tick is several times a
     * cow's -- so fewer of them pay for the barrier.
     */
    private static final int MIN_WIDE_ITEMS_PER_PASS = 8;

    /**
     * @return true when the partition was run in parallel -- cells and serial
     *         tail both -- and false when it declined, leaving everything
     *         untouched for the caller to run itself
     */
    public static <T> boolean run(ServerLevel level,
                                  Partition<T> partition,
                                  Consumer<T> action,
                                  TickMetrics.Phase phase,
                                  String what) {
        SkeinPool pool = Skein.pool();
        if (pool == null) {
            return false;
        }
        partition.fileWide();
        for (int pass = 0; pass < Partition.PASSES; pass++) {
            if (!worthAPass(partition.pass(pass),
                    pass >= Partition.WIDE_PASS ? MIN_WIDE_ITEMS_PER_PASS : MIN_ITEMS_PER_PASS)) {
                partition.collapse(pass);
            }
        }
        int parallelCount = partition.sort();
        if (parallelCount == 0) {
            return false;
        }

        AtomicReference<Throwable> fatal = new AtomicReference<>();
        int cells = 0;
        int passes = 0;
        int deferred = 0;

        for (int pass = 0; pass < Partition.PASSES; pass++) {
            List<Partition.Cell<T>> group = partition.pass(pass);
            if (group.isEmpty()) {
                continue;
            }
            passes++;
            cells += group.size();

            long wallStart = Util.getNanos();
            pool.invokeAll(group.size(), index -> runCell(level, group.get(index), action, phase, what, fatal));
            TickMetrics.recordPhaseWall(phase, Util.getNanos() - wallStart);

            for (Partition.Cell<T> cell : group) {
                deferred += cell.deferred().drain(level);
            }

            Throwable throwable = fatal.get();
            if (throwable != null) {
                partition.release();
                throw new ReportedException(CrashReport.forThrowable(
                        throwable, "Exception ticking " + what + " (Skein " + phase.label() + " phase)"));
            }
        }

        List<T> serial = partition.serial();
        for (int i = 0; i < serial.size(); i++) {
            action.accept(serial.get(i));
        }

        TickMetrics.recordPhase(phase, passes, cells, parallelCount, serial.size(), deferred);
        return true;
    }

    private static <T> boolean worthAPass(List<Partition.Cell<T>> cells, int minItems) {
        if (cells.size() < MIN_CELLS_PER_PASS) {
            // One cell is one task, which the pool runs on the caller anyway --
            // so it is the serial tail with a barrier bolted on.
            return false;
        }
        int count = 0;
        for (Partition.Cell<T> cell : cells) {
            count += cell.items().size();
        }
        return count >= minItems;
    }

    private static <T> void runCell(ServerLevel level,
                                    Partition.Cell<T> cell,
                                    Consumer<T> action,
                                    TickMetrics.Phase phase,
                                    String what,
                                    AtomicReference<Throwable> fatal) {
        long start = Util.getNanos();
        TickContext.Local local = TickContext.local();
        long hits = local.chunkHits;
        long misses = local.chunkMisses;
        long lockNanos = local.chunkLockNanos;
        TickContext.State previous = TickContext.enter(level, true, cell.deferred());
        try {
            List<T> items = cell.items();
            for (int i = 0; i < items.size(); i++) {
                local.ticking = items.get(i);
                try {
                    action.accept(items.get(i));
                } catch (Throwable t) {
                    if (SkeinRefusal.in(t)) {
                        TickContext.resetBrackets();
                        SkeinRefusal.swallowed(items.get(i), t);
                        continue;
                    }
                    TickMetrics.recordFailure();
                    if (SkeinConfig.Live.crashPolicy == SkeinConfig.CrashPolicy.RETHROW) {
                        fatal.compareAndSet(null, t);
                        return;
                    }
                    // One failure is not a reason to abandon the rest of the
                    // cell; vanilla would have crashed here, so say so loudly
                    // and carry on.
                    TickContext.resetBrackets();
                    Skein.LOGGER.error("Skein: {} threw during parallel {} tick in {}",
                            items.get(i), what, level.dimension().identifier(), t);
                    Demotions.threw(items.get(i), level, t);
                }
            }
        } finally {
            local.ticking = null;
            TickContext.exit(previous);
            TickMetrics.recordChunkAccess(local.chunkHits - hits, local.chunkMisses - misses,
                    local.chunkLockNanos - lockNanos);
            TickMetrics.recordPhaseWork(phase, Util.getNanos() - start);
        }
    }
}
