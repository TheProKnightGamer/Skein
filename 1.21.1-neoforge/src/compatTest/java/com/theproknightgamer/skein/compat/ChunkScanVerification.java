package com.theproknightgamer.skein.compat;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.core.ChunkScan;
import com.theproknightgamer.skein.compat.mixin.ChunkMapInvoker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The chunk tick's walks on the pool against the walks themselves, every tick
 * of the stress run: the loop's list holds the holders and chunks a serial walk
 * finds, in the same order, and after the broadcast no holder in it has
 * anything left to send.
 */
public final class ChunkScanVerification {
    private ChunkScanVerification() {}

    private static volatile boolean active;
    private static int previousMin;
    private static long scansBefore;
    private static int walks;
    private static int broadcasts;
    private static int dirty;
    private static int entries;
    private static int toggles;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    static void start(ServerLevel level) {
        previousMin = ChunkScan.minHolders;
        // The test world is small; take the pool path at any size.
        ChunkScan.minHolders = 1;
        scansBefore = ChunkScan.scans();
        active = true;
    }

    public static boolean active(ServerLevel level) {
        return active && level.dimension() == Level.OVERWORLD;
    }

    /** Leaves changes in several ticking chunks for the next broadcast to send. */
    static void beforeTick(ServerLevel level) {
        if (!active) return;
        boolean glass = (++toggles & 1) == 1;
        for (int i = 0; i < 4; i++) {
            BlockPos pos = new BlockPos((4 + 16 * i) * 16 + 8, 200, 4 * 16 + 8);
            level.setBlock(pos, glass ? Blocks.GLASS.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
        }
    }

    /** At the shuffle: the list the loop is about to use, against a serial walk made now. */
    public static void checkWalk(ChunkMap chunkMap, List<?> list) {
        List<ChunkHolder> holders = new ArrayList<>();
        List<LevelChunk> chunks = new ArrayList<>();
        for (ChunkHolder holder : ((ChunkMapInvoker) chunkMap).skeinTest$getChunks()) {
            LevelChunk chunk = tickingChunk(holder);
            if (chunk != null) {
                holders.add(holder);
                chunks.add(chunk);
            }
        }
        check(list.size() == holders.size(), "walk found " + list.size() + " ticking chunks, a serial walk "
                + holders.size());
        for (int i = 0; i < list.size(); i++) {
            ChunkScan.Entry entry = (ChunkScan.Entry) list.get(i);
            check(entry.skein$holder() == holders.get(i) && entry.skein$chunk() == chunks.get(i),
                    "walk entry " + i + " differs from a serial walk's");
            if (changes(entry.skein$holder())) dirty++;
        }
        entries += list.size();
        walks++;
    }

    /**
     * What the loop's {@code getTickingChunk} call answers for {@code holder}.
     * Another mod can redirect that call -- SuperChunk lists FULL chunks with
     * changes still to send, for a render distance past the simulation
     * distance -- and the walk on the pool runs whatever the call became, so a
     * serial walk has to ask the same thing.
     */
    private static LevelChunk tickingChunk(ChunkHolder holder) {
        Method redirect = REDIRECT.get();
        if (redirect == null) return holder.getTickingChunk();
        try {
            return (LevelChunk) redirect.invoke(OWNER.get(), holder);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not ask the redirected getTickingChunk", e);
        }
    }

    /** The chunk source whose tickChunks is running, for the redirect's receiver. */
    private static final ThreadLocal<ServerChunkCache> OWNER = new ThreadLocal<>();
    private static final java.util.concurrent.atomic.AtomicReference<Method> REDIRECT = new java.util.concurrent.atomic.AtomicReference<>();
    private static volatile boolean redirectResolved;

    /** A mixin redirect of the call is merged into ServerChunkCache as {@code redirect$...(ChunkHolder)LevelChunk}. */
    public static void owner(ServerChunkCache source) {
        OWNER.set(source);
        if (!redirectResolved) {
            for (Method method : ServerChunkCache.class.getDeclaredMethods()) {
                if (method.getName().startsWith("redirect$") && method.getReturnType() == LevelChunk.class
                        && method.getParameterCount() == 1 && method.getParameterTypes()[0] == ChunkHolder.class) {
                    method.setAccessible(true);
                    REDIRECT.set(method);
                }
            }
            redirectResolved = true;
        }
    }

    /** After the broadcast: every holder in the loop's list has sent what it had. */
    public static void checkBroadcast(List<?> list) {
        for (Object entry : list) {
            check(!changes(((ChunkScan.Entry) entry).skein$holder()), "a holder with changes was not broadcast");
        }
        broadcasts++;
    }

    private static boolean changes(ChunkHolder holder) {
        ChunkScan.Changes changes = (ChunkScan.Changes) holder;
        return changes.skein$hasChangedSections() || !changes.skein$skyChangedLightSectionFilter().isEmpty()
                || !changes.skein$blockChangedLightSectionFilter().isEmpty();
    }

    static String verify() {
        active = false;
        ChunkScan.minHolders = previousMin;
        long scans = ChunkScan.scans() - scansBefore;
        check(walks >= 50 && broadcasts >= 50, "chunk walks checked " + walks + ", broadcasts " + broadcasts);
        check(scans >= 2L * walks, "only " + scans + " of " + walks + " ticks' walks ran on the pool");
        check(dirty >= walks, "only " + dirty + " holders had changes across " + walks + " walks");
        Skein.LOGGER.info("Chunk scan verification passed: {} ticks' walks ({} entries) matched a serial walk in order, "
                + "and {} broadcasts left no changes behind, {} holders with changes among them", walks, entries,
                broadcasts, dirty);
        return walks + " chunk walks";
    }
}
