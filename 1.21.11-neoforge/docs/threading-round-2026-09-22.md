# What else could be threaded — 2026-09-22

After the [stab column round](stab-power-column-2026-09-22.md), the replay of
the reporting client's world ([fixture](client-world-2026-09-22.md)) was
profiled again, with and without the repeating `stab_power_3` command block, to
find what still ran on one thread. Three things changed.

## 1. The rest of each explosion

A profile of the chain thread with the stab block running showed how little of
each blast was its rays by now:

| Share of an explosion | What |
| --- | ---: |
| Feeding ~1,400 hits into the affected set | 37% |
| Merging the ray slices into a list | 24% |
| `finalizeExplosion`'s block reads | 14% |
| The shuffle in `finalizeExplosion` | 7% |
| The rays themselves, and everything else | 18% |

A blast in the column's open shaft reaches about 1,400 blocks, nearly all of
them air, and vanilla keeps every one: in the set, in the list, in the shuffle,
and read back once more when finalizing, which does nothing to air.

- **No set on the pool path.** Skein's set now keeps first-insertion order
  (vanilla's `HashSet` iterates in hash order, which the shuffle erases), so
  the list it hands over is the blocks in the order the rays first reached
  them. The pool path writes exactly that list straight into `toBlow`, with no
  set and no intermediate list. Positions outside the 16-cube the serial path
  deduplicates, which only a large blast reaches, go through a set of packed
  positions.
- **Held chunks in `finalizeExplosion`.** Its block reads go through the same
  held-chunk memo as the rays'. A held chunk is the chunk object, so a read
  sees every block that an earlier break in the same loop changed.
- **One random source per shuffle.** Each of the shuffle's 1,400 draws asked
  the thread context which source to use; it is now asked once.
- **Draws in bulk.** Every draw from a `LegacyRandomSource` is a
  compare-and-swap on an `AtomicLong`. A blast takes 1,352 for its rays and
  about 1,400 for the shuffle, from a source no other thread can be using.
  `BulkRandom` runs the same 48-bit generator and `BitRandomSource`'s
  `nextFloat` and `nextInt` on a local copy of the seed and publishes the
  final seed with one compare-and-swap, which fails where vanilla's per-draw
  check would have. It applies only to the exact class, and only after a
  startup self-test has shown it matches that class's own output, floats and
  a 1,500-entry shuffle (every bound, powers of two included) on five seeds.

Measured on the stab fixture before the other two changes, interleaved with
the installed build (`d93e03b1…`), load average about 5:

| Build | Mean ms/tick | Median |
| --- | --- | --- |
| Installed | 45.738 (spiky), 40.598 | 37.618, 37.707 |
| + no set, held chunks, one source | 29.693, 30.367 | 28.418, 29.041 |

The profile of that build put the random draws at 23% of what was left, which
is what bulk draws remove; its effect is inside the full build's figures
below.

## 2. The chunk tick's two walks

With render distance 32 there are 4,510 chunk holders, and SuperChunk's
`notickvd` redirect puts every full chunk, not only ticking ones, in
`tickChunks`' list. The tick looks at every holder twice. It asks each for its
ticking chunk (SuperChunk's redirect follows four references per holder), and
at the end asks each to broadcast its changes, which almost none have. Each look
misses the CPU cache. That was about 1 ms and 0.3–0.4 ms of a 5 ms tick.

- The walk hands the loop a snapshot of the holders. At the first holder, the
  wrapped `getTickingChunk` -- vanilla's, or whatever a mod put in its place;
  MixinExtras stacks the wrap on SuperChunk's `@Redirect` -- is called for
  every holder in slices on the pool, and the loop takes each answer in turn,
  only while it stays in step with the snapshot.
- The broadcast is handed only the holders whose `broadcastChanges` would do
  anything: those with changed sections or changed light, found on the pool,
  plus any whose class overrides the method. Light changes reach a holder only
  through the level's main-thread executor, in vanilla and in SuperChunk's
  `onLightUpdate` overwrite, and nothing runs that executor during the
  broadcast.

`randomTicks.parallelChunkScan` turns both off.

## 3. Entity tracking

`ChunkMap.tick` walks the 3,600 tracked entities. For each one it checks for a
section change and, if the entity is in range, calls
`ServerEntity.sendChanges`: 0.7–0.9 ms of the tick, nearly all of it waiting on
memory. The README had listed this as not threaded.

For every entity that is not a player or an item frame, that did not change
section, and whose passengers did not change, `sendChanges` is its own
business: it reads the entity, writes the entity's tracker and dirty flags, and
its only output is `TrackedEntity.broadcast`, to the players already watching.
So it now runs on the pool, with every packet it broadcasts collected instead of
sent. Vanilla's loop then walks only the entities with something left to do:
those with collected packets, which it broadcasts at their turn, and those that
must run live. An entity that is out of range, or whose `sendChanges` produced
nothing, has nothing left and is not walked. Each connection receives the same
packets in the same order. The second loop, for players that changed section,
is untouched. `entities.threadedEntityTracking` turns it off.

## Measurements

Every change together (`810935263a2a…`) against the installed build
(`d93e03b1…`), interleaved. During this batch the machine was also running an
audio job at about 480% CPU and a second Minecraft server at about 150% (load
average 11–20), so every figure is inflated and the means carry spikes of
100–400 ms in both builds. Medians, lower quantiles and the per-part split are
the comparable figures.

**Stab column**, 200 warmup and 600 measured ticks per run:

| Pair | New p10 / p50 / p75 | Installed p10 / p50 / p75 |
| --- | --- | --- |
| 1 | 29.6 / 34.5 / 38.0 | 43.5 / 49.7 / 56.8 |
| 2 | 29.3 / 33.7 / 37.2 | 42.9 / 50.4 / 55.4 |
| 3 | 22.1 / 24.7 / 28.4 | 34.0 / 37.6 / 42.2 |

About a third less at every quantile. The installed build measured 38 ms on
this fixture on a quiet machine, so the column should run near 25 ms there.

**No stab block** (ms per tick; split parts are means):

| Pair | Build | Median | Chunk walk | Loop | Broadcast | Tracking |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 1 | new | **4.092** | 0.584 | 0.482 | 0.232 | 0.191 |
| | installed | 4.622 | 0.838 | 0.488 | 0.351 | 0.707 |
| 2 | new | **5.526** | 0.774 | 0.835 | 0.205 | 0.267 |
| | installed | 7.271 | 1.421 | 0.627 | 0.515 | 1.095 |
| 3 | new | **5.827** | 0.840 | 1.140 | 0.312 | 0.376 |
| | installed | 7.415 | 1.443 | 1.226 | 0.305 | 1.103 |
| 4 | new | **6.020** | 0.903 | 1.059 | 0.450 | 0.484 |
| | installed | 8.220 | 1.318 | 1.500 | 0.510 | 1.102 |

Tracking fell by 56–76% and the walk by 30–46%, and the median tick by 11–27%.
The broadcast is small and noisy, but lower in three pairs of four. The
first pair, taken while the machine was quietest, is the closest to what a
player will see: 4.1 ms against 4.6.

Ticks over 20 ms, new against installed: 1 and 0, 3 and 2, 92 and 14, 26 and
40. The 92 come in runs of consecutive ticks, spend under a quarter of their
time in the entity phase and involve no GC, and the installed build's run in
the same pair shows the same clusters at the same point. They follow the
machine's load rather than either build. On a machine short of cores, though, every parallel phase is a
barrier that can wait on a worker the scheduler has paused, and this round adds
three per tick, so each has its own switch.

**What is left**, from a profile of the new build: the chunk loop's
`isNaturalSpawningAllowed` probes (SuperChunk's list holds every loaded chunk,
not just ticking ones), and on the walk the cost of MixinExtras creating an
`Operation` per holder at a call site another mod has already redirected.
Removing that would mean bypassing vanilla's loop body, which other mods may
hook, so it stays.

## Verification

All nine suites pass. New checks:

- **Explosions.** `ExplosionRaysVerification` now also finalizes: 16 blasts on
  terrain with stone, dirt, TNT, glass, logs, water and obsidian (so drops,
  primed TNT with random fuses and random motion are all exercised). Each one
  is finalized from the same affected list both through vanilla and through
  Skein, outside a pass and from a worker inside one. The blocks left, every
  entity added (type, position, motion, item or fuse) and the next random value
  are compared. 32 finalizations broke 644 blocks identically. The existing 144
  explosions still match the serial loop's ordered blocks. The suite asserts
  the bulk draws passed their self-test and that both the level's source and a
  worker's are the class they apply to.
- **Chunk walks.** `ChunkScanVerification` runs through the stress fixture's
  ticks with the threshold lowered. Every tick, the loop's list is compared
  entry by entry, in order, with a serial walk made at the same moment. After
  the broadcast, no holder in the list may still have changes. A block toggled
  in four ticking chunks every tick ensures some do: 125 ticks, 10,319 entries,
  2,607 holders with changes.
- **Tracking.** `TrackingVerification` puts two identical groups of 32 items,
  32 blocks apart, in front of one fake player. They move the same way every
  tick, cross chunk lines every other tick, send movement every tick and change
  data every fifth. Group A's `sendChanges` runs on the pool; group B is held on
  the live path by a test hook. On each of 120 ticks, every item's packets --
  moves, motion, data, teleports, positions measured from its group -- matched
  its twin's (8,448 packets). Every tick's packets arrived one entity at a time
  in the tracker map's order, and no collected packet went unsent.
