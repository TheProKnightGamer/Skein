# Optimization with compatibility in mind -- 2026-10-09

This round looked for tick time Skein could still save on the 1.21.1 NeoForge line
without giving up anything on compatibility. Five reviewers each read one part of
the code (scheduling, per-call overhead, the entity phase, the block-side phases,
the compatibility layer), and a sixth checked their 27 findings against the code,
merged duplicates and ranked them. Two profiles of the release jar, taken on the
dedicated-server bench, decided the first two changes before the review was in.

## What the profiles showed

`bench/leg.py --jfr` records a Flight Recorder profile of a leg's measured minute,
and `bench/jfrsum.py` summarises it by thread and method.

- **The entity phase's serial tail was larger than the whole parallel part.** A
  villager's follow range is 48, sixteen blocks past the 32-block margin, so its
  footprint fits a 128-block cell over half of each axis: a quarter of positions.
  The other three quarters -- about 110 of the 144 villagers in `entities` -- ran
  one after another on the level thread after every pass. In the profile, that
  tail had more samples than every parallel cell together.
- **The governor could stay on the wrong path for minutes.** Each phase keeps a
  running average of what it costs parallel and serial, and re-times the path it
  is not using now and then. That re-timing moved the stale figure an eighth of the
  way per probe, and probes came up to two minutes apart. One bad parallel tick --
  the first, with cold code, or one taken while the machine was busy -- kept
  entities serial for over a minute and a half (31-50 ms ticks against 24 ms once
  re-timed), and kept random ticks serial with a parallel figure of 65 ms against
  3 ms.

## Changes

1. **A wide grid for wide members** (`Partition`, `ParallelPass`). Whatever the
   ordinary grid cannot hold is filed again into four more passes over a coarser
   grid, sized each tick so seven in eight of those members fit anywhere. Same
   geometry, same guarantee: nothing in one cell reaches another cell of its pass.
   Only footprints narrower than a cell less two margins are admitted -- the ones
   the ordinary grid already puts on workers at some positions -- so endermen,
   ghasts and large contraptions or ships stay on the level thread, where they have
   always ticked and where a chunk they read beyond the loaded margin may be loaded.
2. **The governor re-times properly** (`PhaseGovernor`). A probe's measurement
   replaces the stale figure; a single tick counts for at most four times the
   running average; probes back off (100, 200, 400 ... ticks) only while they keep
   confirming the choice.
3. **Cheaper entity filing** (`LoadedAround`, `EntityTicker`). The loaded-margin
   test remembers holders and rows across neighbouring entities, so a new chunk
   costs 7 holder probes instead of 49; the cluster test asks the entity's type
   before the pinning rules.
4. **The POI lock skips re-entrant acquires** (`PoiManagerMixin`,
   `SectionStorageMixin`). A villager's job-site search entered it about 4,000 times.
   Same lock, same holder; a nested call runs straight through and keeps vanilla's
   lazy stream, because every outermost locked call returns a value or collects
   its stream before letting go.
5. **The `getChunk` wrapper no longer lets its `Operation` escape**
   (`ServerChunkCacheMixin`), so the JIT can drop the allocation the wrapper made on
   every chunk read; it was one in twelve allocated objects on an entity-heavy tick.
6. **The server thread ticks the busiest level** (`DimensionTicker`, `SkeinPool`).
   Levels are dispatched heaviest first and the caller takes the first; before, a
   woken worker often won it, leaving the server thread a light level and the heavy
   level's passes one runner short. A barrier's leftover runners are taken back in
   one sweep instead of one per spin.

## Compatibility fixes found on the way

- **Alternate Current** (in Create Chronicles) keeps one wire handler per level, a
  scratchpad of node maps and queues. Any parallel pass that changes a wire's input
  -- a daylight detector's block entity, a pressure plate under a mob -- entered it,
  and two at once corrupted it. Each thread now has its own handler for each level,
  built by Alternate Current's own constructor on the server thread before levels
  tick and sharing the level handler's config (`AlternateCurrentCompat`,
  `AlternateCurrentLevelMixin`). The new `-PalternateCurrentTest` run drives 100
  detector-wire-lamp circuits through six day/night flips, every detector changing
  in one parallel block-entity pass: without the fix, three runs out of three
  crashed the server inside Alternate Current (`NullPointerException`,
  `ArrayIndexOutOfBoundsException`); with it, every run matched vanilla's wire
  power and lamp state.
- **Create belts** put a landed item into their inventory from
  `updateEntityAfterFallOn`, which calls their own `entityInside`. Skein parked
  `entityInside` for clustered entities but not this path, so two items on one belt
  could write its inventory at once. It is now parked the same way, for blocks that
  both override that hook and react inside (`EntityMixin`,
  `SkeinConfig.Live.afterFallHazards`); vanilla has none, and the suite checks that
  slime blocks and beds are not caught.
- **SpeeDeeEntity 1.0.2** reads the level's tick cache directly for its move memos,
  air-region memo and collision hook, not only through `OFEMUtil.tickCache`, which
  Skein already made per-thread. Every worker therefore read and wrote the same memo
  fields. All of those reads now get the thread's own cache, and a block write ends
  that cache's block epoch (`SpeeDeeCacheHolderMixin`, `SpeeDeeBlockEpochMixin`). The
  suite checks that each thread gets one cache of its own and that a write ends its
  epoch. The cleaner fix is in SpeeDeeEntity itself: route those reads through
  `OFEMUtil.tickCache`.
- **SuperChunk** runs one chunk-executor task after every entity and block tick on
  the server thread (C2ME's mid-tick chunk tasks). Change 6 puts the server thread
  in the busiest level's cells every tick, and inside a cell that task reached the
  level's chunk system while workers were in it: in two of four Create Chronicles
  probe runs, Supplementaries' road-sign structure search had its chunk request
  refused halfway through an entity pass (found with a debug build that logged the
  refusing stack). It could already happen whenever the server thread happened to
  coordinate a level; now it waits while the server thread is inside a cell
  (`SuperChunkMidTickMixin`), and runs as before everywhere else. The cleaner fix is
  in SuperChunk: ask `SuperChunkBridge.ownsCell` before running the task.

## Measurement

`bench/ab.py` compares two builds on one target: every leg is the Skein config
(threadCount 8, nine connected players), the two builds run back to back in an
order that alternates between repetitions, and each scenario is judged by the
median of the per-pair ratios. `--max-load` holds each leg until the box is quiet.

1.0.1 against this round's speed changes (items 1-6), three pairs per scenario,
load 7-10 on a 28-thread machine shared with other work:

| scenario | 1.0.1 | this round | median pair ratio | pairs |
| --- | --- | --- | --- | --- |
| entities | 12.20 ms | 8.79 ms | **0.72** | 0.70, 0.72, 0.76 |
| mixed | 15.02 ms | 11.79 ms | **0.80** | 0.79, 0.80, 0.81 |
| quiet | 4.03 ms | 3.35 ms | **0.83** | 0.85, 0.83, 0.81 |
| random | 4.68 ms | 4.75 ms | 1.01 | 0.98, 1.01, 1.07 |
| hoppers | 3.12 ms | 3.07 ms | 0.99 | 1.05, 0.99, 0.72 |

Every leg's checks passed: entity counts unchanged, all 9,216 hopper items
conserved, nine players online. In `entities` the serial count fell from about 112
to 9 (the players), at the cost of four more barriers. In `quiet` the random-tick
phase now runs in parallel; 1.0.1's governor had kept it serial. `random` and
`hoppers` are unchanged within the noise.

The final jar, with the compatibility fixes below as well, against 1.0.1 again, two
more pairs each at load 6-8: `entities` 0.72 (0.70, 0.75), `mixed` 0.80 (0.80,
0.80), `quiet` 0.86 (0.83, 0.89). Both summaries are in `bench/results/`
(`1.21.1-neoforge-ab-2026-10-09-*`).

## A real pack

Create Chronicles (264 mods, the pack of [compat-packs-2026-10-07](compat-packs-2026-10-07.md))
with the final jar, the same probe as then -- every modded block placed and entity
summoned, in-level phases forced on -- three runs: alive, no hang, 246 hooks with 0
missing and the same 2 left to other mods, all four phases running. Against the run
without Skein, the only lines Skein adds are Iron's Spellbooks' chain lightning (which
also throws without Skein and is demoted after its first failure), as in the previous
round.

## Not done

The judge's "maybe" list, each needing a measurement first: dropping duplicate
parked chunk tickets within a ticket epoch (about 0.1 ms on `random`); a
time-budgeted spin at the barrier tail (may lose under load); finer locking than
`CreateCompat.LOCK` for deployers and brass tunnels (measure contention first); a
last-cell memo in block-entity filing; Sable ship chunks (reproduce first); running
disjoint cells of different passes together (no bench layout shows it). Rejected:
chained worker wake-ups, ordering cells by last tick's cost, prefetching the chunk
loop's checks, seeding the random-tick chunk cache, a bundle of sub-1% tweaks,
pooled entity tracking beside Sable, and pinning create_connected link types.

## Verification

- `coreTest`, with new cases: random wide members never share a reach across cells
  of one pass, a village of follow-range-48 mobs all find cells, huge outliers do
  not coarsen the grid, over-wide members stay serial.
- The in-game suite with SpeeDeeEntity 1.0.2 and without it; the governor test now
  also checks that a stale figure is re-timed within 100 ticks and overturns the
  choice; the demotion test; the new Alternate Current test.
- The SuperChunk suite (`-PsuperchunkTest` with SuperChunk 0.4.1 and SpeeDeeEntity,
  scheduled ticks on): all four in-level phases ran, hopper items conserved, leaves
  decayed and updated.
- Hooks: 187 checked with SpeeDeeEntity, 184 with Alternate Current, 188 with
  SuperChunk and SpeeDeeEntity, 0 missing.

Ported to the other seven lines for 1.1.0 on 2026-10-17, with each mod fix where that mod exists
for the version; see [port-1.1.0-2026-10-17.md](port-1.1.0-2026-10-17.md).
