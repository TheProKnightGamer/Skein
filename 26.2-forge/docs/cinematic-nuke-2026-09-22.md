# Cinematic nuke benchmark and optimization — 2026-09-22

## Workload and measurement

The supplied `Orbital-Strike-Cannon-V7-mod.jar` runs the actual
`orbital:cinematic_nuke` function: 5,875 primed TNT, all with fuse 80.
Every measured run detonated all 5,875 on frame 80, removed them all, and
reported zero Skein tick failures or quarantined dimensions.

- Intel Core i7-14700K, 28 logical processors; Java 21.0.12.
- Minecraft 1.21.1 / NeoForge 21.1.219; dedicated server, no connected players.
- Skein with `general.useAllCpuThreads=true`, 27 workers plus the server thread,
  adaptive scheduling enabled, other production defaults.
- SpeeDeeEntity from the user's client instance: `speedeeentity-neoforge-1.21.1-1.0.2.jar`.
- Fixed 4 GiB heap, fresh JVM and fresh superflat world for each run.
- 33×33 chunks preloaded and forced around (2080, 32), with a short stone target
  column above the flat ground. The launch point is (2080.5, -59, 32.5).
- 60 warmup ticks, followed by the real function and 80 fuse ticks.
- Elapsed time brackets `ServerTickEvent.Pre` / `Post`; the summon command,
  initial terrain generation, result writing, and shutdown saves are excluded.
- Detonations are counted with `ExplosionEvent.Detonate`; removal alone does
  not count as an explosion. Each tick also records remaining TNT, parallel
  entity counts, entity-phase wall time, and JVM garbage-collection time.

This is a small local before/after sample, not a statistical guarantee. Entity
random streams, natural mob spawns, scheduling, and system load vary between
runs. Client rendering, network traffic to players, and deeper natural terrain
are outside this measurement. The baseline already includes earlier Skein
optimizations and SpeeDee compatibility; it is not unmodified Minecraft.

## Results

| Build | Explosion tick samples | Mean |
| --- | --- | --- |
| Before | 5,192.441 / 5,442.967 ms | 5,317.704 ms |
| Optimized | 4,129.167 / 3,865.179 / 3,894.220 ms | 3,962.855 ms |

The mean explosion tick is **25.5% shorter**, a **1.34× speedup**, saving about
1.35 seconds in this fixture. Final runs span **3.87–4.13 seconds**. Ordinary
flight-tick medians were 16.42–19.08 ms in those runs. Per-run values, including
GC times, are also in [the results CSV](cinematic-nuke-2026-09-22.csv).

The intermediate fluid-read/set change alone took 4,669.052 ms. Separate JFR
runs took 6,100.452 ms before and 4,384.797 ms after the full changes. Sampling
adds overhead, so those two timings are excluded from the unprofiled comparison.
Garbage collection accounted for tens of milliseconds, not the multi-second stall.

## Changes

1. **Avoid duplicate fluid lookup in each explosion ray step.** The block state
   has just been read, and the normal chunk's fluid state comes from that same
   palette entry. Reuse `state.getFluidState()` instead of repeating the chunk,
   height, and palette lookups. The original path remains for clients, debug
   worlds (which synthesize block states), and disabled Skein.
2. **Use an open-addressed affected-block set.** `ObjectOpenHashSet` replaces
   the temporary `HashSet<BlockPos>` while Skein is enabled on the server.
   This removes per-entry hash-map nodes and speeds up repeated deduplication.
   The affected-position set is preserved; iteration order differs, so the
   subsequently shuffled block destruction order is not bit-for-bit identical.
3. **Separate chunk-cache hits from misses.** Ticket handling, published-chunk
   lookup, and the loading lock move into a separate helper. The frequently
   executed cache-hit wrapper can inline more easily. Cache invalidation,
   ownership, locking, and deferred-ticket behavior are unchanged.
4. **Hide removed entities while registry teardown is deferred.** Both typed
   and untyped section queries skip removed entities during a parallel pass.
   Vanilla would already have removed those entries. This prevents later TNT
   from performing damage/exposure work on TNT that already detonated, and
   restores immediate query visibility without mutating shared indexes early.

The explosion's ray directions, random power draws, resistance callbacks,
entity damage, knockback, and finalization continue through Minecraft's code.
No exposure cache is reused across explosions or intervening block changes.

## Profile findings

JFR execution sampling was enabled only around the detonation tick, at 1 ms.
Minecraft's `/debug` profiler was not used because it makes Skein choose serial
execution and would change the workload being measured.

The baseline's hottest sampled methods included palette reads (2,286 samples),
`HashMap.putVal` (1,162), `ServerChunkCache.getChunk` (1,040), and Skein's chunk
wrapper (671). The later recording showed the chunk wrapper at 138 samples and
the new set's `add` at 312. Recordings have different total sample counts and
JIT inlining, so these counts identify hot spots rather than isolated speedups.

The remaining hot path is the vanilla ray loop and palette reads. The dense
blast also leaves unequal amounts of work in its spatial cells. More workers
cannot independently process mutually interacting TNT without changing the
ordering and world state later explosions observe. The measured tick still
exceeds the 50 ms budget substantially.

## Verification and reproduction

`ExplosionVerification` runs 32 seeded paired comparisons with Skein's explosion
optimization disabled/enabled. It checks affected block sets, resistance and
veto callback traces, remaining RNG state, entity damage, and knockback. Cases
include mixed stone/glass/bedrock, water, waterlogged slabs, empty sky, the lower
world boundary, and a callback that directly mutates a chunk during ray traversal.
It also asserts that typed and untyped queries stop returning a discarded TNT
before its deferred buffer is drained, then verifies teardown after the barrier.

The integration fixture also passed the SpeeDee cache/collision checks,
512 moving entities over 100 cluster/grid ticks, and two `stab_power_3` plus
two `nuke_power_1` strikes (1,666 TNT). Tests live in the optional `compatTest`
source set and are excluded from the production jar.

The final `build` and core geometry/ordering/barrier checks passed. A separate
server without SpeeDeeEntity also passed all 32 explosion comparisons and both
deferred-removal query checks. The packaged jar contains the new mixins and no
benchmark/test classes. Output: `build/libs/skein-1.0.0.jar`, SHA-256
`c4123e31179b9a56d4ed484ac1680f5859a52cc95ae13729f33d94a961e1a9bd`.
That measured jar is now archived at `build/bubble-benchmark/precheck/skein-1.0.0.jar`.
The [subsequent snowball regression check](bubble-column-2026-09-22.md) made the
removed-entity query filter cheaper. A cinematic rerun with that correction
took **3,717.731 ms**, with all 5,875 TNT detonating on tick 80 and zero tick
failures. This later run is stored in `samples/snowball-fix/` and is not mixed
into the original three-run mean above. The current jar's SHA-256 is
`68e4ab5bb8daaaa600427da69f54776207da83f5047d5147486eaa7dce358178`.

The supplied cannon jar still reports its existing 30 function and 12 recipe
parse failures on this Minecraft version. The direct strike functions used
here load and execute; this benchmark does not establish that every recipe
or control function in the supplied jar works.

```bash
./gradlew build --offline --console=plain

./gradlew runSpeedeeTest --offline --console=plain \
  -PcinematicBenchmark \
  -PspeedeeTestJar=/path/to/mods/speedeeentity-neoforge-1.21.1-1.0.2.jar \
  -PorbitalTestJar=/path/to/mods/Orbital-Strike-Cannon-V7-mod.jar
```

Add `-PcinematicProfile` for JFR. Omit `-PcinematicBenchmark` to run the behavior
and SpeeDee/Orbital integration checks. `-PspeedeeTestJar=none` without the
Orbital argument verifies the explosion checks and loading without SpeeDee.

The task reuses the workspace's existing EULA agreement and creates disposable
worlds under `build/`. Raw cinematic measurements are in
`build/cinematic-benchmark/samples/`: baseline `1` and `2`, intermediate
`fluid-and-set`, final `optimized-*`, and the two `profile-*` directories.

Input SHA-256:

```text
Orbital: 5ff97de21c391ba958e03bdd241d0fb75bab1f9e0cc166119e70fc266676a0c1
SpeeDee: 3abbeb8ec25764612c7a8e3064b5029f9f6448fcccf31872ca997a8f9be3c5d4
```
