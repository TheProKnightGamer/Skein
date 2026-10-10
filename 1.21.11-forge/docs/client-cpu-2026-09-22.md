# CPU use, cinematic TNT, and client-instance configuration

The inspected instance already had `general.useAllCpuThreads=true`. Skein
created 27 workers on the 28-logical-processor machine. The pool limit was not
the cause of the low utilization.

## Observed blockers

1. The instance log showed four active Lithium options blocking all in-level
   phases: `alloc.chunk_random`, `util.world_border_listener`,
   `world.block_entity_ticking.world_border`, and `block.hopper`.
2. The installed SuperChunk 0.3.0 rejected dimension workers with
   `ConcurrentModificationException: Async ticking server chunk manager`.
   Skein quarantined the affected dimensions and returned them to serial ticks.
3. The broader concurrent-collision fixture exposed a further Lithium race in
   `chunk.entity_class_groups`: first queries concurrently mutate its section's
   `Reference2ReferenceArrayMap`, producing an array-bounds exception and a
   corrupted cache. Skein now includes this option in its compatibility gate.
4. Even after enabling the worker phases, adaptive scheduling could choose
   serial TNT countdown ticks and retain that choice for the detonation tick.
   A new TNT hint requests a parallel probe for the next entity phase when the
   fuse reaches its final countdown tick. Eligibility, minimum counts, spatial
   partitioning, and barriers still decide whether work may run concurrently.
   Fuse, motion, and explosion logic are unchanged. The hint expires after one
   phase and cannot bypass config, profiler, or compatibility restrictions.

SuperChunk generates `lithium.properties` from `superchunk.properties` at
startup. Editing the generated file alone does not persist. Skein's warning
now points to the owning file and prints `lithium.*` keys when SuperChunk is loaded.

## Tested settings for the installed SuperChunk jar

In `config/skein-common.toml`, merge these keys into the existing sections:

```toml
[general]
useAllCpuThreads = true
adaptive = true
ignoreModConflicts = false
logSummaryEveryTicks = 100

[dimensions]
enabled = false

[entities]
enabled = true
threadedProjectileQueries = true
```

In `config/superchunk.properties`:

```properties
lithium.alloc.chunk_random=false
lithium.util.world_border_listener=false
lithium.world.block_entity_ticking.world_border=false
lithium.world.chunk_access=false
lithium.block.hopper=false
lithium.chunk.entity_class_groups=false
```

`world.chunk_access` was already disabled. Keep SuperChunk's existing chunk
serialization, generator-settings, and tick-scheduler pins as well. These are
targeted changes, not replacement minimal configuration files. The user's other
settings were retained in the staged fixture, including scheduled ticking.
Restart Minecraft after changing startup mixin settings.

Disabling dimension threading keeps whole-level ticks on the server thread;
the entity, random-tick, and block-entity worker phases are separate settings
and can still use the pool. This workaround applies to the installed SuperChunk
jar, SHA-256
`9540842fa68af4331b73411e88fe0e7809552f483338978f48961e432a7f421d`.
SuperChunk development and its newer compatibility policy are handled in another
session. A newer bridge may impose additional restrictions: a dimension-only
policy which pins entity threading off will override the setup above.
The renamed Skein API lives under `com.theproknightgamer.skein`.

## Measured useful CPU work

Actual `ThreadMXBean` CPU time was sampled around the tick for the server thread
and all Skein workers. CPU milliseconds divided by tick wall milliseconds gives
average busy cores for those threads. It excludes rendering, GC, JIT, and other
mods' background pools; it is not whole-process CPU utilization.

| Fixture | Explosion tick | Average busy cores | Entity cells |
| --- | ---: | ---: | ---: |
| Skein + SpeeDee, before burst hint | 4,267.428 ms | 2.157 | 18 |
| Installed SuperChunk/Lithium, before burst hint | 5,253.084 ms | 0.998 | 0 |
| Installed SuperChunk/Lithium, final settings + burst hint | 3,924.521 ms | 2.144 | 22 |

All three detonated exactly 5,875 TNT on frame 80 and recorded zero tick failures.
The final row returned 5,903 entities through the spatial scheduler, including
the TNT and incidental entities. All 27 workers accumulated at least 1 ms CPU
in that tick; that does not mean 27 cores were busy for the entire tick.
The before/after modpack rows also differ by the newly disabled group cache,
so the timing change is not an isolated measurement of the hint alone.
These are individual local diagnostic runs, not a statistically stable speedup.
Raw per-tick rows: [CSV](client-cpu-2026-09-22.csv).

The remaining limit is the width and balance of independent work. Overlapping
explosions must observe earlier block destruction, damage, and knockback.
Twenty-seven available workers cannot turn one dependent chain into 27 safe
tasks. A 2.14-core tick averages only about 7.7% of 28 logical CPUs for the
measured threads. Increasing the worker count further cannot remove that limit.
Further explosion threading would need to identify independent read-only work
inside an explosion and preserve resistance callbacks and mutation order.

## Verification and artifacts

The final configuration passed 32 seeded explosion comparisons, the projectile
candidate/hit/damage/event suite, 512 concurrent query comparisons, the TNT hint
and eligibility checks, and 100 parallel stress ticks with 512 moving items
(50 cluster ticks, 50 grid ticks). Both production and standalone core checks
passed. Tests run in disposable worlds; no client save was opened or changed.

The snowball query optimization and its separate 3.31× benchmark are documented
in [the projectile report](projectile-query-2026-09-22.md).
The final corrected SuperChunk/Lithium configuration also passed the
2,000-snowball bubble test at 2.643 ms mean per tick, with identical recorded
motion and all snowballs retained.

Skein's author is now **TheProKnightGamer**. Production and test namespaces,
Mixin configuration, build coordinates, documentation references, and IDE path
templates were updated. Shareable source and jar contents were scanned for the
old personal name and home paths. Local runtime logs, worlds, and caches are
excluded from the source archive; those private working directories are not
release material.

Final jar: `build/libs/skein-1.0.0.jar`, SHA-256
`94b8147ff72e0f6b025b342354d880f1b9e9886bb99edc0d2a2efa9323fc07c6`.
Clean source archive: `build/distributions/skein-1.0.0-sources.zip`.
The jar and configuration changes are prepared for installation; the live
client instance was not modified by this work.

```bash
./gradlew build sourceArchive --offline
./gradlew runSpeedeeTest --offline -PcinematicBenchmark -PcinematicCpu \
  -PspeedeeTestJar=/path/to/mods/speedeeentity-neoforge-1.21.1-1.0.2.jar \
  -PorbitalTestJar=/path/to/mods/Orbital-Strike-Cannon-V7-mod.jar \
  -PextraTestModsDir=/path/to/additional-test-mods \
  -PtestConfigDir=/path/to/staged-config \
  -PtestDirectory=build/cinematic-combination-test
```

The extra mod directory contains the installed SuperChunk and Lithium jars.
The real mod folder is used so SuperChunk's bootstrap service loads, not just
its ordinary classes. The supplied Orbital jar retains its existing unrelated
recipe/function parse errors; the direct cinematic function loads and executes.
