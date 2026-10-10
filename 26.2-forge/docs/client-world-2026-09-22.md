# A real client world, replayed — 2026-09-22

After the [bystander fix](bystander-isolation-2026-09-22.md) the 1,880-snowball
column no longer ran on one thread, and the next question was what was left.
Single-feature fixtures had stopped being representative, so this benchmark
replays the reporting client's own world on a dedicated server.

## The fixture

`ClientWorldBenchmark` (`-PclientWorldTemplate=...`) loads a copy of the
client's save — the nine region files around the player, the forced chunk, the
other dimensions, player data — and logs its owner in over an in-memory
connection (`BenchConnection`). Like the integrated server's local connection,
it never encodes packets; like a real client, it acknowledges chunk batches and
answers keep-alives. The player's saved position (774.45, 77.5, −327.3), game
mode and inventory load from their own player data.

- Render distance 32, simulation distance 20, as the client ran them.
- Command blocks enabled, as they always are in singleplayer. The world's
  forced chunk runs four powered repeating command blocks with tag selectors
  (`tp`, `ride`, `data merge`).
- The Orbital Strike Cannon datapack's per-tick functions.
- 3,031 snowballs: 1,880 in the column beside the player, 1,151 in a second pile
  outside simulation distance. The client crashed before the column's blocks
  were written, though the snowballs were saved, so the fixture rebuilds the
  column (soul sand, two bubble blocks, a glass ring) under them before the
  player joins.
- The client's own `skein-common.toml`, `superchunk.properties`,
  `lithium.properties` and SuperChunk 0.4.0, Lithium 0.15.4, SpeeDeeEntity
  1.0.2, Orbital, purgatory and FerriteCore jars.
- 4,510 chunks loaded. The run waits until loading and sending settle (at
  least 600 ticks, then 200 with no change), warms up for 200 ticks and
  measures 600.

A test-only mixin set times `tickChunks` in stages, entity tracking, the
entity phase and datapack functions. It also records vanilla's own profiler
sections, which is how the parts below were measured. None of it is in the
gameplay jar.

## What was found

| Part (ms per tick, before) | Cause | Change |
| --- | --- | ---: |
| Mob census, 0.50 | `tickChunks` walks every entity for mob caps before checking `doMobSpawning`, which is off here | refreshed once a second while spawning is off |
| Datapack functions, 0.55 | `@e[type=marker]` selectors walk all 3,600 entities; `EntityLookup` has no type index | insertion-ordered per-type buckets |
| Entity workers, 1.33 | `hasChunk`, several times per entity, ends in a synchronized map inside SuperChunk's chunk system; every worker queued on it | answered from the per-thread chunk cache |
| Entity workers | every worker rebuilt SpeeDeeEntity's collider list, walking the whole level, once per tick | one shared list per level per tick |
| Entity tracking, 0.68 | a distance-manager probe per tracked entity | per-walk table of answers |
| Chunk sender (with SuperChunk's `sendAtChunkSending=false`) | outer ring never sendable; singleplayer re-checks every pending chunk each tick | config: `player.sendAtChunkSending=true` |

The tracking table removes the probes, but the walk over 3,600 tracked entities
is bound by memory access, so its total did not measurably change. It is exact
and kept.

With `sendAtChunkSending=false`, only 1,852 of the 4,510 loaded chunks were
ever sent to the player. Render distance 32 behaved like a radius of about 21.
With it on, 4,492 are sent. That changes what the player sees, and holding
the extra chunks costs the client memory. It also ends the per-tick rescan.
With the new jar, the fixture measured 6.346 and 6.291 ms with the setting off
and 5.354 ms (three runs, below) with it on.

On the client, the snowballs' hit queries now skip other vanilla snowballs and
eggs, as they already did inside server passes. The live client profile had nine
tenths of the render thread in client-side snowball ticks, scanning 3.5 million
candidates per client tick. That path was not measured here; the benchmark has
no client.

## Results

Three runs of each jar, interleaved (before, after, after, before, before,
after), on the fixture with `sendAtChunkSending=true`. One server at a time, on a
28-thread machine that was also running an unrelated workload (load
average about 5):

| Build | Runs (mean ms/tick) | Mean | Median | p95 |
| --- | --- | ---: | ---: | ---: |
| Before (`88cc83d6…`) | 6.632, 6.592, 6.685 | 6.636 | 6.260 | 8.126 |
| After (`44a05db1…`) | 5.268, 5.138, 5.656 | 5.354 | 5.084 | 6.793 |

**19% less tick time** on average, and 16% off the 95th percentile. Split per
tick (first pair):

| Part | Before | After |
| --- | ---: | ---: |
| Entity phase, total | 1.919 | 1.316 |
| of which worker wall | 1.389 | 0.732 |
| Chunk holder walk | 0.876 | 0.923 |
| Mob census | 0.496 | 0.030 |
| Per-chunk loop and random ticks | 0.800 | 0.778 |
| Chunk broadcast | 0.263 | 0.189 |
| Entity tracking | 0.678 | 0.753 |
| Datapack functions | 0.545 | 0.227 |

On the client's config as it stood (`sendAtChunkSending=false`), the new jar
measured 6.346 and 6.291 ms; the old jar measured 6.622 ms in one run from an
earlier, differently loaded session.

The single bubble column regression fixture (2,000 snowballs, no players), run
interleaved in the same session: old jar 1.342 and 1.803 ms, new jar 1.168 and
1.106 ms. With the bystander from the previous report: 1.490 ms, every entity
tick through the cluster tier.

## What is left

At about 5 ms, the largest remaining parts are vanilla's per-chunk bookkeeping
at render distance 32 (holder walk, per-chunk checks and broadcast, about
1.8 ms), which walks every loaded chunk every tick; entity tracking (0.75 ms),
which walks every tracked entity; and the command blocks' tag selectors (about
0.5 ms), which walk every entity. None of these walks can be skipped without an
index kept up to date by hooks elsewhere: the chunk walk would need one inside
SuperChunk's chunk system, and tags would need one covering every way a tag set
can change, including mods writing to `getTags()` directly.

## Verification

All real-server suites pass with SpeeDeeEntity installed, including two new
ones. `LookupVerification` checks that typed walks return exactly what the full
walk returns, in order, through 6,000 adds, removals, re-adds, refused duplicate
UUIDs and early aborts, with Skein on and off. `IsolationVerification` covers the
bystander fix. `coreTest` passes, including filtered `PointGrid` queries.

```sh
./gradlew runSpeedeeTest --offline \
  -PspeedeeTestJar=/path/to/speedeeentity-neoforge-1.21.1-1.0.2.jar \
  -PclientWorldTemplate=build/client-world/template \
  -PtestConfigDir=build/client-world/config-send \
  -PextraTestModsDir=build/client-world/mods \
  -PbenchmarkSkeinJar=build/libs/skein-1.0.0.jar
```

The world template, configs and mods are local copies from the client instance
and are not part of the source tree.
