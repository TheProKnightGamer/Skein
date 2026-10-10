# Single bubble column regression check — 2026-09-22

This compares the packaged Skein build from immediately before the cinematic
nuke optimizations with the packaged build delivered after those changes.

## Fixture

- Exactly 2,000 snowballs, initially overlapping at (2080.5, 71.25, 32.5).
- One 1×1 column: soul sand at y=69, upward bubble blocks at y=70–71,
  glass sides, open top. The snowballs rise out and fall back naturally.
- No teleporting, freezing, velocity resets, or replacement snowballs.
- Dedicated NeoForge 21.1.219 / Minecraft 1.21.1 server, Java 21.0.12,
  i7-14700K, 4 GiB fixed heap; no connected players.
- The user's SpeeDeeEntity 1.0.2 jar is installed in both cases.
- Identical Skein settings: all-CPU mode (27 workers), adaptive and cluster
  scheduling enabled. Mob spawning and random block ticks are disabled.
- Fresh JVM and superflat world each time; 25 chunks preloaded and forced.
- 40 settling ticks, then snowballs spawn. Their first 200 ticks warm up the
  JVM/scheduler; the next 400 ticks are measured between server Pre/Post events.
- Spawn, setup, validation, CSV writing, and shutdown saves are excluded.

The fixture checks every snowball after every tick: alive, same tick count,
still above the original column, and within the vertical test area. At the
end all 2,000 must have ticked exactly 600 times. Tick failures and quarantined
dimensions fail the test. Raw CSVs also record cluster/grid scheduling, entity
wall time, GC time, and the first snowball's height and vertical velocity.

## Results

The first nuke build added overhead in this dense-query case. The query hook
has now been revised, and the final runs show no regression against the build
from before the nuke changes.

| Build | Mean ms/tick, run 1 / run 2 | Combined mean | Combined entity wall mean |
| --- | --- | --- | --- |
| Before nuke changes | 5.528 / 4.722 | 5.125 ms | 3.823 ms |
| First nuke build | 5.163 / 7.043 | 6.103 ms | 4.894 ms |
| Revised query hook | 4.684 / 4.604 | 4.644 ms | 3.621 ms |

Each row covers 800 measured ticks across two separate JVMs. Runs were ordered
before / first nuke / before / first nuke, followed by profiling and the two
revised runs. The first nuke build's mean was 19.1% higher in this small sample;
the revised build's mean is 9.4% below the pre-nuke mean. The wide spread between
individual JVMs means those percentages should not be treated as universal
speed changes. The practical result is recovery to the baseline range or better.

All six runs retained all 2,000 snowballs. Every recorded height and vertical
velocity matched across the six runs. Adaptive scheduling used the cluster
path on 398–399 of 400 measured ticks, with occasional serial probes. See
[the results CSV](bubble-column-2026-09-22.csv) for per-run means, medians, p95,
and entity-phase wall time.

JFR sampling of the first nuke build found 803 of 2,240 execution samples under
the new removed-entity wrapper; 785 samples ended in `Entity.isRemoved`.
Profiling timings are excluded from the table. A dense pile causes each
snowball to query the same section repeatedly, making per-candidate overhead
significant even though none of the snowballs are being removed.

The correction folds the removal filter into the existing bounding-box
condition, leaving the consumer invocation untouched. Live entities need only
the removal-state check; thread context is consulted only for removed entities.
This keeps deferred removals invisible to both typed and untyped queries while
avoiding the extra operation wrapper on every candidate.

This small local sample can reveal a clear regression; it cannot establish
identical performance on every CPU or in a full client/modpack. The fixture
includes normal server work and adaptive serial probes. Initial world-generated
entities may exist outside the column. The column's snowball count is fixed.

## Build identity and reproduction

Only `EntitySectionMixin`, `ExplosionMixin`, `ServerChunkCacheMixin`, and the
mixin list differ between the pre-nuke and first nuke jars. The final correction
changes only `EntitySectionMixin` in production. The cluster scheduler itself is
unchanged. Of those changes, removed-entity filtering and the chunk lookup
wrapper can affect snowballs; the explosion-only mixin is not exercised here.

The optional `benchmarkSkeinJar` Gradle property stages the selected jar as
the server's Skein mod and excludes current main classes from its runtime
classpath. The fixture compares loaded class/resource bytes against the
requested jar to prevent accidentally benchmarking the current build twice.

```text
Before SHA-256: 95f0082141cbc49458e349dc8732ff90576b9d8ce4d3be79a645822e5ccb1ec6
First nuke SHA-256: c4123e31179b9a56d4ed484ac1680f5859a52cc95ae13729f33d94a961e1a9bd
Revised SHA-256: 68e4ab5bb8daaaa600427da69f54776207da83f5047d5147486eaa7dce358178
SpeeDee SHA-256: 3abbeb8ec25764612c7a8e3064b5029f9f6448fcccf31872ca997a8f9be3c5d4
```

```bash
./gradlew runSpeedeeTest --offline --console=plain \
  -PbubbleBenchmark \
  -PbenchmarkSkeinJar=build/cinematic-benchmark/baseline-sources/skein-1.0.0.jar \
  -PspeedeeTestJar=/path/to/mods/speedeeentity-neoforge-1.21.1-1.0.2.jar
```

For the current build, use `-PbenchmarkSkeinJar=build/libs/skein-1.0.0.jar`.
Both runs reuse the workspace's existing EULA agreement and create disposable
worlds under `build/bubble-benchmark/`. Saved raw outputs are in that directory's
`samples/before-*`, `samples/after-*`, and `samples/fixed-*` subdirectories.
The original nuke jar is archived under `build/bubble-benchmark/precheck/`.

The build, core verification, 32 seeded explosion comparisons, typed/untyped
deferred-removal tests, SpeeDee collision/cache checks, and 512-entity cluster/grid
stress tests passed with the revised hook. The benchmark fixture is excluded
from the production jar. No user-instance configuration or mods were changed.
The final run without SpeeDeeEntity also passed all explosion comparisons and
query checks, including retention of a live neighbor while a removed entity
is awaiting teardown.

A cinematic nuke rerun with the revised hook took **3,717.731 ms**. All 5,875
TNT detonated on tick 80 with zero tick failures, retaining the explosion gains.
Its raw output is in `build/cinematic-benchmark/samples/snowball-fix/`.
