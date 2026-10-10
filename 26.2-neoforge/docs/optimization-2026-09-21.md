# Scheduling optimizations — 2026-09-21

This change reduces partitioning and dispatch overhead without widening entity
reach, relaxing spatial isolation, changing configured thresholds, or enabling
additional phases by default.

## Changes

- **Entity precedence:** stop scanning once a predecessor reaches the highest
  level already assigned. Reject a graph as soon as its level count exceeds
  either the existing density limit or the cluster tier's minimum useful width.
  Skip clearing level storage on the independent-entity path, which only uses
  the identity order.
- **Scheduled ticks:** retain integer bucket-node indices with items, resolve
  components once per bucket, replace the root hash map with indexed arrays,
  probe only four neighbours per bucket, and use weighted union with path
  compression. Bucket keys retain both full signed coordinates. Group creation
  and item order remain deterministic.
- **Isolation:** reject positions outside the indexed outsiders' bounding box
  before performing the 27 bucket probes.
- **Dispatch:** share an indexed batch between at most `workers + 1` queued
  runners. All four dispatch sites use one callback per batch instead of
  constructing a callback for every cell, component, or slice. Helpers still
  participate in nested batches. Unexpected task exceptions are delivered to
  the submitting thread only after every runner exits.
- **Entity collection:** avoid classification below the population threshold and
  isolation below the cluster threshold. Release all snapshots on small-world
  fallbacks and exceptional exits as well as successful parallel ticks.

## Microbenchmarks

Measured on an Intel Core i7-14700K, OpenJDK 21.0.12, with a fixed 512 MiB heap.
Both versions used the same seeded datasets and benchmark body. Each workload
warms for 1.5 seconds, then reports the median of seven samples lasting at least
250 ms. The pool workload uses four workers and 1,024 preconstructed no-op tasks;
its allocation measurement covers the submitting thread.

| Workload | Before, µs/op | After, µs/op |
| --- | ---: | ---: |
| Dense conflict graph, 10,000 entities | 150,106.70 | 63.50 |
| Sparse conflict graph, 20,000 entities | 4,406.90 | 4,265.42 |
| Mixed reaches, 20,000 entities | 13,278.23 | 12,145.21 |
| Independent entities, 20,000 | 9.81 | 4.47 |
| Scheduled-tick grouping, 20,000 positions | 3,666.13 | 1,528.04 |
| Isolation, 20,000 distant queries | 2,555.48 | 13.21 |
| Isolation, 20,000 nearby queries | 3,621.21 | 2,389.50 |
| Dispatch, 1,024 tiny tasks | 197.96 | 22.89 |

Dispatch allocation fell from **49,192 to 208 bytes per batch** in this workload
(99.6% lower). Dense-graph rejection and distant isolation deliberately measure
cases where the new early exits apply. These are algorithm microbenchmarks,
not measurements of overall server tick time. Absolute timings vary with JIT
compilation, CPU scheduling and workload; small differences need longer profiling
before being treated as meaningful.

Run `./gradlew coreBenchmark --offline` to repeat the current measurements.
The original source snapshot and raw comparison output from this workspace are
under `build/optimization-baseline/`; the optimized output is
`build/optimization-benchmark.txt`. Build artifacts are temporary, and a clean
build removes that comparison snapshot.

## Verification

`./gradlew build --offline` runs the core verification suite, including:

- 1,500 randomized precedence comparisons against a quadratic reference,
  inclusive boundaries, zero-reach entities, explicit level limits and reuse
  after rejection.
- 20,000 point-query comparisons against linear search, with empty/rebuilt
  indexes and inclusive boundaries.
- 400 randomized component comparisons against an independent bucket-box
  reference, checking membership and original item order, plus world-border
  coordinates.
- Repeated grid partitioning and margin checks.
- Exactly-once execution, nested submissions and concurrent callers with zero,
  one and four workers; publication of task writes; empty batches; failure
  propagation only after the barrier closes; nested failure ownership.

These tests compile the actual production algorithms using position-only entity
fixtures, no-op deferred buffers and real worker threads. They do not load
Minecraft or simulate its world mutations. The fixtures are confined to the
`coreTest` source set and are not shipped in the mod jar.

A separate NeoForge 21.1.219 server smoke test uses a temporary world under
`build/optimization-smoke/`, 16 workers, forced parallel eligibility and the
rethrow crash policy. It exercises 1,024 stationary snowballs, 256 cows, 128 hoppers
in separate cells and two distant flowing-water sites while all three dimensions
tick. Status confirmed 64 cluster slices, two grid entity cells, two hopper
cells and two scheduled-tick components. Final entity counts remained 1,024
snowballs and 256 cows. No tick failures, stalls or exceptions were logged, and
the server saved and stopped normally. The smoke test is a runtime compatibility
check, not a before/after server performance comparison.
