package com.theproknightgamer.skein.compat;

import com.theproknightgamer.skein.Skein;
import com.theproknightgamer.skein.SkeinConfig;
import com.theproknightgamer.skein.core.PhaseGovernor;
import com.theproknightgamer.skein.core.SkeinLevel;
import com.theproknightgamer.skein.core.TickMetrics.Phase;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.PrimedTnt;

/** Deterministic historical estimates, followed by a real transformed TNT countdown. */
final class GovernorVerification {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static void run(ServerLevel level) throws Exception {
        PhaseGovernor governor = ((SkeinLevel) level).skein$governor();
        var pField = PhaseGovernor.class.getDeclaredField("parallelNanos");
        var sField = PhaseGovernor.class.getDeclaredField("serialNanos");
        pField.setAccessible(true);
        sField.setAccessible(true);
        long[] parallel = (long[]) pField.get(governor);
        long[] serial = (long[]) sField.get(governor);
        long[] oldParallel = parallel.clone(), oldSerial = serial.clone();
        // The live level's probe counters, held so no scheduled probe lands inside the checks.
        var sinceField = PhaseGovernor.class.getDeclaredField("sinceProbe");
        var confirmedField = PhaseGovernor.class.getDeclaredField("confirmed");
        sinceField.setAccessible(true);
        confirmedField.setAccessible(true);
        int[] since = (int[]) sinceField.get(governor);
        int[] confirmed = (int[]) confirmedField.get(governor);
        int[] oldSince = since.clone(), oldConfirmed = confirmed.clone();
        boolean adaptive = SkeinConfig.Live.adaptive;
        PrimedTnt tnt = new PrimedTnt(level, .5, 90, .5, null);
        try {
            SkeinConfig.Live.adaptive = true;
            parallel[Phase.ENTITIES.ordinal()] = 100_000_000;
            serial[Phase.ENTITIES.ordinal()] = 1;
            since[Phase.ENTITIES.ordinal()] = 0;
            confirmed[Phase.ENTITIES.ordinal()] = 5;
            check(!governor.begin(Phase.ENTITIES, true), "countdown estimate should prefer serial");
            governor.finish(Phase.ENTITIES);

            tnt.setFuse(2);
            tnt.setOldPosAndRot();
            tnt.tick();
            check(tnt.getFuse() == 1 && !tnt.isRemoved(), "hint changed the fuse or detonated early");
            governor.begin(Phase.RANDOM_TICKS, false);
            governor.finish(Phase.RANDOM_TICKS);
            check(governor.begin(Phase.ENTITIES, true), "imminent TNT did not request a parallel probe");
            governor.finish(Phase.ENTITIES);
            check(!governor.begin(Phase.ENTITIES, true), "burst hint did not expire after one phase");
            governor.finish(Phase.ENTITIES);

            tnt.setFuse(2);
            tnt.setOldPosAndRot();
            tnt.tick();
            check(!governor.begin(Phase.ENTITIES, false), "burst bypassed compatibility/config eligibility");
            governor.finish(Phase.ENTITIES);
            check(!governor.begin(Phase.ENTITIES, true), "ineligible burst hint leaked into another tick");
            governor.finish(Phase.ENTITIES);
            for (int fuse : new int[]{1, 0, -1}) {
                tnt.setFuse(fuse);
                check(tnt.getFuse() == fuse && !tnt.isRemoved(), "short-fuse hint changed TNT state");
                check(governor.begin(Phase.ENTITIES, true), "new short-fuse TNT missed its first parallel probe");
                governor.finish(Phase.ENTITIES);
                check(!governor.begin(Phase.ENTITIES, true), "short-fuse hint did not expire");
                governor.finish(Phase.ENTITIES);
            }
            tnt.setFuse(1);
            check(!governor.begin(Phase.ENTITIES, false), "short fuse bypassed phase eligibility");
            governor.finish(Phase.ENTITIES);

            // One bad parallel figure (a cold or contended tick) is overturned by
            // the next probe, which comes within the minimum interval.
            int e = Phase.ENTITIES.ordinal();
            parallel[e] = 50_000_000;
            serial[e] = 20_000_000;
            since[e] = 0;
            confirmed[e] = 0;
            int probedAt = -1;
            for (int tick = 1; tick <= 200 && probedAt < 0; tick++) {
                if (governor.begin(Phase.ENTITIES, true)) {
                    probedAt = tick;
                }
                governor.finish(Phase.ENTITIES);
                serial[e] = 20_000_000;
            }
            check(probedAt > 0 && probedAt <= 100, "a stale parallel figure was not re-timed within 100 ticks: " + probedAt);
            check(parallel[e] < 20_000_000, "a probe did not replace the stale figure: " + parallel[e]);
            check(governor.begin(Phase.ENTITIES, true), "the governor did not switch after the probe");
            governor.finish(Phase.ENTITIES);
            Skein.LOGGER.info("Governor verification passed: countdown and newborn short-fuse hints, unchanged fuses, one-shot probes, eligibility veto, and stale figures re-timed");
        } finally {
            tnt.discard();
            SkeinConfig.Live.adaptive = adaptive;
            System.arraycopy(oldParallel, 0, parallel, 0, parallel.length);
            System.arraycopy(oldSerial, 0, serial, 0, serial.length);
            System.arraycopy(oldSince, 0, since, 0, since.length);
            System.arraycopy(oldConfirmed, 0, confirmed, 0, confirmed.length);
        }
    }
}
