# Syncing the 1.21.11 line with 1.21.1 — 2026-09-22

The 1.21.11 line was last brought level with 1.21.1 on 2026-09-21 at about
20:21. Since then the 1.21.1 line gained a day of work from several sessions,
and the 1.21.11 line had kept fixes of its own. This sync brings 1.21.11 level
with everything in 1.21.1 that applies to it, keeps what 1.21.11 had that 1.21.1
does not, and ports the verification suites so both lines are checked the same
way.

A copy of the tree before the sync is in
`skein/backups/skein-1.21.11-before-sync-2026-09-22.tar.gz`.

## Kept from 1.21.11

None of these were in 1.21.1, and all of them stay:

- **No chunk loads from parallel ticks.** A worker's read of an unloaded chunk
  is refused, not loaded under a lock, and `entities.loadedMarginChunks` (3)
  pins entities near the edge of the loaded area to the level thread. This
  follows a 2026-09-09 crash: a `LeveledPriorityQueue` corrupted by a
  worker-side load.
- **Player chunk tracking parked for the barrier.** `ServerChunkCache.move`,
  `addEntity` and `removeEntity` called from a parallel tick are parked,
  following the 2026-09-12 and 2026-09-17 crashes.
- **Priority 1500 on `Block`, `Entity`, `Projectile` and
  `ServerCommonPacketListenerImpl` mixins,** with `require = 0` where the PvP
  bot mod's herobot mixins overwrite the target.
- **`BlockStateBaseMixin`,** the 1.21.11 form of parking a block's effect on an
  entity inside it: 1.21.11 makes the call from a lambda, so the hook is on the
  callee.
- Every API difference: the profiler, `Util`, `Identifier`, ticket storage,
  `forEachBlockTickingChunk`, the neighbour updater, raid and random-sequence
  signatures, and so on.

## Brought over from 1.21.1

- **Package and author.** `com.theproknightgamer.skein` → `com.theproknightgamer.skein`;
  author TheProKnightGamer.
- **Config.** `general.useAllCpuThreads`,
  `entities.threadedProjectileQueries`, `entities.parallelExplosionRays`,
  `entities.threadedEntityTracking`,
  `entities.cluster.exactProjectileIsolation`, and the new Lithium wording.
- **Projectile queries.** `ProjectileQueries`, `ThrowableProjectileMixin`,
  `LevelEntityGetterAdapterMixin`, `EntitySectionMixin` (which also hides an
  entity whose removal waits for the barrier), and the Level-side wrap.
  Vanilla snowballs and eggs now live in `projectile.throwableitemprojectile`.
- **Bystander isolation.** `ClusterPass`'s exact test of what a snowball can
  hit, and `PointGrid`'s filtered query. 1.21.11's hit margin is
  `computeMargin`, at most the 0.3 the test pads by.
- **Entity lookup and spawn census.** Type-bucketed `EntityLookup`, and a
  census reused while mob spawning is off (the `tickChunks(ProfilerFiller,
  long)` overload, `GameRules.SPAWN_MOBS`).
- **Chunk cache.** `hasChunk` from the per-thread cache, and the level owner's
  private cache in front of the normal fetch. 1.21.11's refusal path for
  parallel misses is unchanged.
- **SpeeDeeEntity 1.0.2 compat.** Per-thread tick caches, block-change
  invalidation and the shared collider index. The 1.21.11 jar's classes have
  the same signatures as the 1.21.1 jar's. `LevelChunk.setBlockState` takes
  `int` flags here.
- **SuperChunk and Lithium.** `SuperChunkBridge`, the Lithium chunk-status
  owner mixin, and `LithiumCompat`, which now reads Lithium's startup config
  and knows three more conflicting options:
  - `chunk.entity_class_groups`;
  - `entity.collisions.unpushable_cramming`;
  - `util.block_entity_retrieval`.
- **TNT burst probes** for the governor.
- **Explosions,** rewritten for `ServerExplosion`:
  - rays on the pool in `calculateExplodedPositions`, with the insertion-ordered
    set, first-hit dedup and held chunks;
  - exposure on the pool in `hurtEntities`;
  - held chunks and bulk shuffle draws in `interactWithBlocks`;
  - `BulkRandom`.

  1.21.11 works out exposure once per entity (1.21.1 did it twice), so
  `ExplosionDamageCalculatorMixin` has no counterpart. The block reads in
  `Level.getBlockState` are guarded by `isInValidBounds` here, and the ray and
  held-chunk reads use the same guard.
- **Entity tracking on the pool.** `TrackingPass` and its mixins, adapted
  twice:
  - 1.21.11 sends through three `ServerEntity.Synchronizer` methods, so each
    collected send records which one and is replayed through it;
  - the loop also calls `sendChanges` for a pending `needsSync`, which
    `sendChanges` clears, so an out-of-range entity with a pending sync stays
    live. The tracking suite found this: its packets were collected on the pool
    and then never asked for.

## Left out

- **The chunk tick's two walks (`ChunkScan`).** 1.21.11's chunk tick visits
  only spawn candidates and entity-ticking chunks, and broadcasts only holders
  queued as changed, so there is no walk over every holder to thread. The
  class keeps only the pool-slicing helper the tracking pass uses.
- **Benchmark fixtures** (stab, bubble, cinematic, client world) and the
  Orbital and SuperChunk suites. They need worlds and companion jars built for
  1.21.1.

## Lithium's explosion raycast

Lithium 0.21.4 (1.21.11) turns on `world.explosions.block_raycast`, which Lithium
0.15.4 (1.21.1) does not. It replaces `calculateExplodedPositions`' ray loop
through the same constant Skein uses. The first sync attempt crashed on the first
explosion. Skein now casts rays from inside that hook and marks it optional, so:

- **Lithium present:** Lithium holds the loop, its serial raycast gives the
  blocks, and nothing is cast twice.
- **Lithium absent:** Skein holds the loop and casts on the pool as before.

Lithium's `entity_raycast`, which rewrites `getSeenPercent`, keeps all its state
per call, so exposure on the pool is safe alongside it.

## Verification

The 1.21.1 harness was ported: `runSpeedeeTest`, `SpeedeeVerification`, and the
chunk-cache, explosion, explosion-ray, projectile, isolation, lookup, governor
and tracking suites, plus the core tests. Every run below passed every suite:

| Run | Extra mods and config |
| --- | --- |
| Plain | SpeeDeeEntity 1.0.2 |
| Lithium | + Lithium 0.21.4 with its nine conflicting options off |
| PvP server setup | + PvP bot 0.0.15 and Lithium with the PvP server's own `lithium.properties`, plus `mixin.util.block_entity_retrieval=false` |
| Optional mods absent | none: optional mixins stay out |
| Lithium defaults | none: boots, and switches the in-level phases off with the nine options listed |

Test changes the port needed:

- **Explosion probes.** 1.21.11 makes `PrimedTnt.hurtServer` final, so the
  probes are `Snowball` subclasses: not living, so their exposure comes from
  the pool.
- **Explosion steps.** The suites drive `ServerExplosion`'s private steps
  through an invoker, to keep 1.21.1's "rays and entities first, then break"
  structure.
- **Stale-exposure fixture.** 1.21.11 works out an entity's exposure before
  hurting it, so whichever probe is hurt first now drops stone in front of the
  other. It refused the cached answer in 8 trials.
- **Direct ticks.** `setOldPosAndRot()` is called before each direct `tick()`,
  as `tickNonPassenger` does. 1.21.11 sweeps block effects from the old
  position.
- **Impact expectations.** NeoForge 1.21.11 applies a cancelled projectile
  impact to position only; the hit still happens. The absolute expectation
  moved to what 1.21.11 does. The indexed and plain query paths must still end
  the same way.
- **Stress fixture.** The stress cells are forced together with a
  `loadedMarginChunks`-wide ring, or the margin keeps every stress entity on
  the level thread.

The port also found a weak check in both lines. A chunk loaded last during a
suite's terrain setup stays invisible to entity queries until its FULL
promotion task runs, and that promotion runs on the next load. So one of the
two explosion probes was never in the level's index. The suites now run the
queued chunk tasks after building terrain, and require both probes to be hit
in a quarter of the trials or more. Both lines pass with that.

```sh
./gradlew runSpeedeeTest --offline --no-daemon \
  -PspeedeeTestJar=/path/to/speedeeentity-1.0.2.jar \
  [-PextraTestModsDir=dir-with-lithium-and-pvp_bot -PtestConfigDir=dir-with-lithium.properties]
./gradlew coreTest --offline --no-daemon
```
