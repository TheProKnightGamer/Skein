# Scheduling optimizations, second pass — 2026-09-21

This pass targets the work left after the [first optimization](optimization-2026-09-21.md).
Its baseline is that already-optimized build, not the original mod.

## Changes

- **Mixed entity populations:** if fewer than a quarter of the entities have
  positive reach, index each positive predecessor into every bucket its reach
  intersects. Zero-reach entities query their own bucket for incoming conflicts.
  Positive entities additionally query their own outgoing radius. A pair
  conflicts if either entity reaches the other, so this computes the same
  precedence relation as the reference algorithm.
- **Shorter scans:** each insertion chain caches the maximum precedence in its
  remaining suffix. A query stops as soon as that bound cannot increase the
  current assignment. Checking the entity's own bucket first also lets dense
  conflicts finish before searching neighboring buckets.
- **Spatial queries:** buckets are twice the maximum reach, usually reducing
  range queries from 27 buckets to eight. Bounds round outward, including the
  rounding of the distance comparison itself. Exact distance checks still
  determine conflicts. Integer bucket saturation cannot overflow query loops.
- **Small and shrinking populations:** outsider indexes of at most eight points
  use direct scans; larger indexes clear only the active table prefix after a
  population shrinks. The distant-query rejection remains a small method that
  HotSpot can inline independently of the full spatial search.
- **Cell partitioning:** offset and cell index are computed together, eliminating
  repeated division and floor operations. Cell lists use an in-place sort with
  the existing total ordering: largest first, then key. The same order requires
  no temporary sort arrays. Serial collapse copies directly between reusable
  object lists instead of constructing an intermediate array.

The mixed-population index uses additional reusable primitive buffers; selecting
it only below the positive-reach threshold limits indexing work. Its loop is
separate from the ordinary conflict loop so HotSpot can optimize both strategies
independently when the server encounters different populations.

## Measurement

Same Intel Core i7-14700K and OpenJDK 21.0.12 as the first pass, fixed 512 MiB
heap, seeded inputs, 1.5-second warmup per workload, median of seven samples of
at least 250 ms each. Comparisons use the same benchmark body in separate JVMs.
Both JVMs are pinned to logical CPUs `0,2,4,6,8`, five distinct performance cores,
to avoid migrating between the processor's performance and efficiency cores.
The saved baseline is `build/optimization-round2-baseline/`; current raw results
are in `build/optimization-round2-benchmark.txt`, with the matched baseline in
`build/optimization-round2-baseline/pinned-benchmark.txt`. JFR sampling used while tuning
is saved as `build/optimization-round2.jfr`. These are temporary build artifacts.

| Workload | First optimized build, µs/op | Second pass, µs/op |
| --- | ---: | ---: |
| Dense graph, 10,000 entities | 29.69 | 38.75 |
| Sparse graph, 20,000 entities | 3,752.90 | 2,598.42 |
| Mixed reaches, 20,000 entities | 15,773.29 | 4,500.65 |
| Spread-out mixed reaches, 20,000 entities | 4,283.17 | 951.30 |
| Independent entities, 20,000 | 5.65 | 4.18 |
| Scheduled grouping, 20,000 (unchanged control) | 2,133.08 | 1,690.27 |
| Cell partition, 20,000 points | 4,589.84 | 2,402.14 |
| Cell partition, 20,000 spans | 2,556.70 | 2,238.38 |
| Isolation, 20,000 distant queries | 19.44 | 14.58 |
| Isolation, 20,000 nearby queries | 3,229.71 | 3,003.85 |
| Dispatch, 1,024 tasks (unchanged control) | 21.89 | 25.62 |

Mixed workloads improved substantially across the tuning runs. This final pair
shows 3.5× and 4.5× reductions, respectively. Other gains are less consistent:
dense-graph rejection is about 9 µs slower in this pair, and even the unchanged
controls vary. CPU affinity reduces one source of noise but does not eliminate
background load or frequency changes. This is not a claim that every input is faster.

The cell-partition benchmarks allocate **0 bytes per operation** after warming,
down from **62,784 bytes** for 20,000 points and **29,952 bytes** for 20,000 spans.
These figures measure scheduling algorithms, not server TPS. Small timing
differences are subject to JIT and machine noise; unchanged scheduled-tick
grouping and dispatch are included as controls.

Run `./gradlew coreTest coreBenchmark --offline` to verify and measure the current
implementation. `coreTest` also runs as part of `build`.

## Verification

The reference suite now also checks:

- 480 randomized populations around the strategy cutoff, with mixed fractional
  reaches and reuse of the same index across strategies.
- Incoming and outgoing conflicts at inclusive and adjacent floating-point
  boundaries, including the world border and tiny radii with saturated bucket
  coordinates.
- 50,000 randomized boundary cases verifying conservative bucket bounds.
- Switching outsider indexes between linear and hashed searches, growing and
  shrinking populations, and query radii smaller/larger than the build radius.
- Exact grid assignments at boundaries for even and odd cell widths, multiple
  margins and span widths; deterministic cell sorting and original item order.

The NeoForge smoke test used 16 workers, forced parallel eligibility and the
rethrow crash policy in the existing temporary test world. Adding 128 stationary,
non-merging items to 1,024 snowballs exercised the mixed strategy in Minecraft:
1,152 cluster entities ran across two precedence levels and 73 slices. Another
256 cows ran across two grid cells, and 128 hoppers across two cells. All entity
counts matched after running, no failures or stalls were logged, and the server
saved and stopped normally. The log is `build/optimization-round2-smoke.log`.

The full build and tests passed. The packaged jar contains production classes
only; no Minecraft test stand-ins are included.
