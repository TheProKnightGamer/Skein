# SuperChunk parallel ticking verification — 2026-09-22

The updated Skein and SuperChunk builds allow parallel entities, random block
ticks, block entities, and opted-in scheduled ticks. Previously SuperChunk pinned
all four phases off and allowed only dimension threading. Install both updated
jars and restart; their version labels are unchanged.

## Compatibility changes

SuperChunk disables nine conflicting Lithium options before mixin selection,
including when standalone Lithium replaces its bundled copy. Skein checks the
actual startup configuration, so editing a properties file cannot make an
already-applied unsafe mixin appear disabled. The options and their explanations
are in [Running with Lithium](../README.md#running-with-lithium).

The versioned bridge distinguishes a dimension owner from a cell worker. A cell
can drive chunk lifecycle callbacks only for its own level while holding that
level's chunk-load lock. Reloads and command transactions wait for the relevant
barriers. Parallel ticking requires isolated level random sources; unsupported
or older combinations retain the restrictive compatibility policy.

SuperChunk continues to handle chunk generation, lighting, and serialization.
Skein's parallel saving remains disabled. The configured tick phases remain
enabled through reloads, including when dimension threading is disabled.

## Paired performance measurements

These compare the previous dimension-only combination against both updated
production jars. Each workload ran twice per combination, first before/after,
then after/before, with one benchmark server at a time and no concurrent builds
or verification servers. Results are local measurements, not a guarantee for
other worlds or modpacks.

Environment: Core i7-14700K (28 logical processors), Java 21, Minecraft 1.21.1,
NeoForge 21.1.219, 4 GiB heap, flat world, SpeeDeeEntity 1.0.2, bundled Lithium.
Skein used `useAllCpuThreads=true` (27 workers), adaptive scheduling, and
`crashPolicy="RETHROW"`. SuperChunk used CPU world generation with four workers.
Scheduled tick parallelism was off for these performance runs and enabled for
the correctness runs below.

| Workload | Before, mean | After, mean | Less tick time |
| --- | ---: | ---: | ---: |
| 2,000 snowballs in one bubble column | 37.169 ms | 2.328 ms | 93.7% |
| Cinematic nuke, explosion tick | 5,725.790 ms | 3,254.921 ms | 43.2% |

Each bubble run used 200 warmup and 400 measured ticks. Before-run means were
41.113 and 33.226 ms; after-run means were 2.469 and 2.186 ms. All 2,000 snowballs
stayed in the column and ticked exactly 600 times. The recorded representative
snowball's position and vertical velocity matched between builds in all 400
samples of both pairs. The updated runs each recorded 800,000 clustered entity
ticks; the old compatibility policy allowed none.

The cinematic workload used the supplied `Orbital-Strike-Cannon-V7-mod.jar`, with
1,089 chunks preloaded. All 5,875 TNT detonated on frame 80, in a single explosion
tick, in every run. Before explosion times were 5,611.708 and 5,839.872 ms; after
times were 3,335.703 and 3,174.139 ms. Tick failure counters stayed at zero and no
dimension was quarantined. This checks detonation count and timing, not complete
world-state equivalence after an explosion.

Measured CPU time across the tick threads increased from about one busy core
to 2.01–2.12 busy cores during the explosion tick. The new runs used 16–20 entity
cells. Enabling workers removes the old compatibility restriction, but this
workload still cannot occupy all 27 workers continuously.

Some preparation logs contain `Unprimed heightmap: WORLD_SURFACE_WG` diagnostics
from chunk generation, including an original-build run. The observed messages
precede benchmark warmup. They are not a clean-log result and remain outside
this tick-compatibility change.

Per-run summaries are in [the CSV](superchunk-parallel-2026-09-22.csv). Full local
logs, tick CSVs, and jar manifests are under
`build/superchunk-parallel/performance/`.

## Correctness checks

Both production builds passed their regression checks; SuperChunk's optional
bridge suite passed 120 checks. Real server tests passed with bundled Lithium,
standalone Lithium 0.15.4, and standalone Lithium with dimension threading off.
Every run exercised all four parallel phases and checked:

- 288 hopper/chest pairs transferred items while retaining exactly 64 items each.
- 96 unsupported leaves decayed through random ticks.
- 192 persistent leaves updated their distance through scheduled ticks.
- Four concurrent cell tasks loaded missing chunks and released chunk-task ownership.
- A live config reload retained the negotiated phase policy.
- SpeeDeeEntity verification completed 100 parallel ticks with 512 moving entities,
  split equally between the cluster and grid schedulers.

Observed parallel work counts:

| Configuration | Scheduled ticks | Random chunks | Entities | Block entities |
| --- | ---: | ---: | ---: | ---: |
| Bundled Lithium | 1,018 | 10,464 | 25,700 | 31,828 |
| Standalone Lithium | 908 | 10,464 | 25,750 | 32,046 |
| Standalone Lithium, dimensions off | 1,037 | 9,888 | 25,750 | 30,282 |

A separate packaged-jar server on NeoForge 21.1.248 passed fresh-world generation
of 1,089 requested chunks, saving, restart, tick-function reloads, command-block
reloads, local and cross-dimension `spreadplayers`, a conditional command chain,
a powered command minecart, and hopper transfers in three dimensions. Its health
checks reported no errors. Results are in
`build/superchunk-parallel/packaged-verification/result.json`.

All verification used disposable worlds. The client instance was not modified.

## Artifact identity

Both final jars were built from their actual source directories. The SuperChunk
jar matches the staged jar used by the integration tests byte for byte.

| Artifact | SHA-256 |
| --- | --- |
| Updated Skein 1.0.0 | `8cb46811607aacaec179effae8a865066545c0d3b4fa4c5ab1abcf6cda34fe20` |
| Updated SuperChunk 0.4.0 | `9e35c9981fbaa4313bd60367559714c66f3ceeb2c40604470443fcb230977c22` |
| Previous Skein 1.0.0 | `94b8147ff72e0f6b025b342354d880f1b9e9886bb99edc0d2a2efa9323fc07c6` |
| Previous SuperChunk 0.4.0 | `2f57c421a3f5d36dd0c7861726cc231bf333252275bf408077060be158aab624` |
| Orbital Strike Cannon V7 | `05d085b9308212539245042d81363e59c53779ba3ce0cb852a8164125796953d` |
