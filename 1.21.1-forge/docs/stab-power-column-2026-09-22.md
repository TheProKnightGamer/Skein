# A repeating stab — 2026-09-22

In the [client world replay](client-world-2026-09-22.md), a repeating,
always-active command block running `function orbital:stab_power_3` was placed
12 blocks east of the player, at (786, 70, −327). The function summons 202
fuse-1 TNT in one vertical line: two at every second block, from 80 above the
command block to 120 below. So it produces 202 explosions every tick,
sustained, all in one column.

## Why it cannot spread

Every explosion in the column pushes the charges next to it and breaks blocks
their rays will read, and the next charge moves before it explodes. Two
blasts two blocks apart are a real dependency. The chain runs the full length
of the column, so a scheduler that keeps vanilla's order has to run it
serially, and Skein does: one thread, 202 explosions in a row, 26 workers
idle. The earlier [stab-nuke report](stab-nuke-2026-09-22.md) found the same
thing. What can change is the cost of one explosion, and whether that
explosion can use the idle workers.

## Changes

1. **Held chunks.** An explosion casts 1,352 rays of about 23 steps each,
   and every step asked the chunk source, through Skein's per-thread cache
   and SuperChunk's wrapper, for a chunk the explosion already had. Rays now
   read from the chunks the explosion holds, at most four. The first read of
   each chunk still goes through `getChunk`.
2. **Rays on the pool.** Rays only read the world, since nothing breaks until
   `finalizeExplosion`. The random draw for each ray is taken first, in
   vanilla's order, on the calling thread. The rays are then cast in slices of
   64 on idle workers, each inside a normal Skein worker context. The hits are
   fed into the affected set in ray order, and vanilla's loop runs no
   iterations. Anything a helper parks (a chunk ticket) joins the caller's
   buffer in slice order. This applies only to vanilla damage calculators with
   no source or a TNT or creeper source.
3. **First hits only.** A ray visits each block about three times, and rays
   overlap near the centre: about 27,000 hits for about 1,400 blocks. Each
   slice, and the merge, keeps only the first hit of each block within the
   16-cube the serial path already deduplicates, so the set receives the same
   first insertions in the same order.
4. **Exposure on the pool.** Vanilla works out how much of each nearby entity
   the blast can see twice, once for damage and once for knockback, with
   about 30 raycasts each time. For entities that are not living, the answer is
   now worked out once on the pool, as soon as the entity list is final. It is
   used only while the entity's box is unchanged and no block in the level has
   changed since, tracked by a new per-level block-change counter. Otherwise
   vanilla computes it live.

`entities.parallelExplosionRays` (on by default) turns items 2–4 off.

## Measurements

Client world fixture, `sendAtChunkSending=true`, 200 warmup and 400 measured
ticks unless noted. Each row adds one change to the row above it.

| Build | Runs (mean ms/tick) | Entity phase |
| --- | --- | ---: |
| Installed (`44a05db1…`) | 124.896 (600 ticks), 124.156 | 119.6 |
| + held chunks | 104.959, 98.101 | 94.4–100.4 |
| + rays on the pool | 83.951 | 80.0 |
| + first hits only | 44.996, 45.724 | 40.5–41.3 |
| + packed hits, one random lookup | 42.462, 46.313, 45.505 | 37.9–41.4 |
| + exposure on the pool (`d93e03b1…`) | **38.008, 37.877** | 32.9 |

The last row is about **69% less** than the installed jar: the server now runs
this command block at 20 TPS with about 12 ms to spare. The previous build,
`88cc83d6…`, measured 121.017 ms on the same fixture.

Without the stab block, the new build is indistinguishable from the installed
one. Interleaved runs: installed 5.285, 5.484, 4.340 and 5.618 ms; new 5.014,
4.196 and 5.578 ms, plus one run at 7.540 ms taken while machine load was
high. That run's spikes did not repeat in the later pairs. Bubble column with
a bystander: installed 1.235, 1.414 and 1.380 ms; new 1.503, 1.386 and 1.430
ms.

## Verification

`ExplosionRaysVerification` runs 48 seeded trials: no source, TNT and creeper;
radius 1 to 6; open sky, mixed terrain with water and waterlogged blocks, and
the bottom of the world; a centre on a chunk corner. Each trial compares:

- rays on the pool against Skein's serial loop: the ordered affected-block
  list, the next random value, and the damage and knockback of two probe
  entities;
- the same against vanilla with Skein off: the block set (Skein's set has
  always iterated in a different order from vanilla's `HashSet`), the random
  value, damage and knockback;
- both paths again from inside a worker pass.

In 12 open-sky trials the first probe's `hurt` drops a stone block between the
centre and the second probe, mid-explosion. In 8 of them that block changed
vanilla's result, and the pool path matched vanilla there too, because the
cached exposure was refused. A counter confirms every parallel variant actually
ran on the pool. The existing 32 seeded explosion comparisons, with custom
resistance callbacks, still pass, as do all other suites.

```sh
./gradlew runSpeedeeTest --offline --no-daemon \
  -PspeedeeTestJar=/path/to/speedeeentity-neoforge-1.21.1-1.0.2.jar \
  -PclientWorldTemplate=build/client-world/template \
  -PtestConfigDir=build/client-world/config-send \
  -PextraTestModsDir=build/client-world/mods \
  "-PclientWorldCommand=function orbital:stab_power_3" \
  -PbenchmarkSkeinJar=build/libs/skein-1.0.0.jar
```
