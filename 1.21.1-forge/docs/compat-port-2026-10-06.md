# Compatibility port from 1.21.1 NeoForge -- 2026-10-06

Ports the 2026-10-05 compatibility round
(`1.21.1-neoforge/docs/compat-lithium-superchunk-carpet-worldedit-2026-10-05.md`)
to this line.

- **Lithium**: `mods.toml` declares a `lithium:options` table turning off the nine
  options that break in-level threading, so installing both needs no config.
  Lithium has no Forge build; a port that reads Lithium's mod overrides honours the table. A user's own config still wins, and the existing guard then keeps the
  in-level phases off with a message saying the option was turned back on.
- **WorldEdit**: `LevelChunkMixin` and `SpeeDeeBlockChangeMixin` name vanilla's
  `setBlockState(BlockPos, BlockState, boolean)` by full descriptor (`Z` flag), so
  the block-change hooks never land on WorldEdit's added overload.
- **Carpet** (`CarpetCompat`): the hook check tolerates carpet's
  `noBlockCalcsWithNoBLockDamage` redirect in `Explosion.explode`, rays are not cast
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

SpeeDeeEntity could not be tested on this line: its Forge build for this version
fails to start a server even with Skein absent (Forge 1.21.1: its nested
MixinExtras 0.5.3 fails to load; Forge 1.21.11: its `LevelCacheMixin` targets the
SRG name `m_6933_` on a loader that runs Mojang names).

Benchmark harness, `mixed` scenario (1440 penned animals, 576 hoppers in rings,
a random-tick farm, 480 animals in the nether), nine bot players, dedicated
server: correctness checks pass (entity counts and item totals unchanged),
165 hooks, 0 missing, 0 error lines, no Skein warnings; dimensions,
random ticks, entities and block entities all ran in parallel (entities
4.68x overlap). Core tests pass.
