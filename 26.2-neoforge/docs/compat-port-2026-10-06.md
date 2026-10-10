# Compatibility port from 1.21.1 NeoForge -- 2026-10-06

Ports the 2026-10-05 compatibility round
(`1.21.1-neoforge/docs/compat-lithium-superchunk-carpet-worldedit-2026-10-05.md`)
to this line.

- **Lithium**: `neoforge.mods.toml` declares a `lithium:options` table turning off the nine
  options that break in-level threading, so installing both needs no config.
  Lithium 0.25.3 reads the table. A user's own config still wins, and the existing guard then keeps the
  in-level phases off with a message saying the option was turned back on.
- **WorldEdit**: `LevelChunkMixin` and `SpeeDeeBlockChangeMixin` name vanilla's
  `setBlockState(BlockPos, BlockState, int)` by full descriptor (`I` flag), so
  the block-change hooks never land on WorldEdit's added overload.
- **Carpet** (`CarpetCompat`): the hook check tolerates carpet's
  `noBlockCalcsWithNoBLockDamage` redirect in `ServerExplosion.calculateExplodedPositions`, rays are not cast
  on the pool while `explosionNoBlockDamage` is on, and the held-chunk block read
  goes through to the redirect while it is on. Tested only on 1.21.1 NeoForge,
  the one loader and version with a carpet build here; without carpet nothing
  changes.
- **Tests**: where this line has the in-game suite, the explosion finalize
  trials accept a `LegacyRandomSource` subclass (SuperChunk's checked random),
  which `BulkRandom` already leaves to its own draws.
- **README**: the Lithium section, the SuperChunk package note, and a carpet and
  WorldEdit section.

## Verification

In-game suite, plain and with Lithium 0.25.3 at its defaults: chunk cache,
explosions, explosion rays, projectiles, isolation, lookups and the governor
pass, 0 errors; Lithium "9 override(s) found". With SpeeDeeEntity 1.0.2 (NeoForge
26.2) and Lithium 0.25.3 the whole suite passes, tracking and the parallel-tick
stress included: 100 parallel ticks (50 cluster, 50 grid) with 512 moving
entities, 100 tracking ticks, SpeeDeeEntity's checks, 179 hooks, 0 missing, 0
errors.

The suite had never compiled on 26.2 (37 errors since the 2026-09-30 port). It
now uses 26.2's names: `EntityTypes.*`, `ChunkPos.pack`, `Entity.entityTags`,
the record accessors of `ClientboundSetEntityMotionPacket`, and
`Level.getRandom()`. Once it ran, the finalize trials failed on one item drop's
velocity: 26.2 draws a dropped item's starting velocity from the item's own,
freshly seeded random rather than the level's (1.21.11 reads `Level.random`), so
two vanilla runs already disagree on it. The comparison leaves item velocity
out on 26.2; blocks, drops, positions and the level's random stream still have to
match. A failed comparison now names the part that differs.

Benchmark harness, `mixed` scenario (1440 penned animals, 576 hoppers in rings,
a random-tick farm, 480 animals in the nether), nine bot players, dedicated
server: correctness checks pass (entity counts and item totals unchanged),
170 hooks, 0 missing, 0 error lines, no Skein warnings; dimensions,
random ticks, entities and block entities all ran in parallel (entities
4.29x overlap). Core tests pass.
