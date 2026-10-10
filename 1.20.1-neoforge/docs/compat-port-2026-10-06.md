# Compatibility port from 1.21.1 NeoForge -- 2026-10-06

Ports the 2026-10-05 compatibility round
(`1.21.1-neoforge/docs/compat-lithium-superchunk-carpet-worldedit-2026-10-05.md`)
to this line.

- **Lithium**: `mods.toml` declares a `lithium:options` table turning off the nine
  options that break in-level threading, so installing both needs no config.
  Radium reads the key from the mod's own `[[mods]]` entry rather than the file, so on this line the table is declared in both places; SuperChunk's Forge 1.20.1 port reads the file-level one. Canary has not been checked. A user's own config still wins, and the existing guard then keeps the
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

**SpeeDeeEntity fix (MixinExtras 0.5.4).** With SpeeDeeEntity 1.0.2 installed, Skein's
`SpeeDeeEntityCollidersMixin` failed to apply (FATAL "mismatching param at index
1"): it declares SpeeDeeEntity's `TickCache` parameter as `@Coerce Object`, which
`@WrapMethod` accepts only from MixinExtras 0.5, and this line bundled 0.4.1.
Every other line runs 0.5.x. The bundled MixinExtras is now 0.5.4. This predates
the port: no test had run this line with SpeeDeeEntity before. Afterwards, with
SpeeDeeEntity installed: the server boots with 162 hooks, 0 missing, no mixin
failures, 0 errors; and the harness `mixed` scenario passes (correctness checks,
162 hooks, 0 missing, 0 errors, entities 4.60x). Without it the same scenario passes
with 159 hooks (entities 4.80x), and Radium at its defaults still finds its
overrides. All of these used the final jar.

Radium 0.12.4 at its defaults on NeoForge 47.1.106, dedicated server: Radium "10
override(s) found", Skein reports both running with every phase on, 160 hooks, 0
missing, 0 errors.

Benchmark harness, `mixed` scenario (1440 penned animals, 576 hoppers in rings,
a random-tick farm, 480 animals in the nether), nine bot players, dedicated
server: correctness checks pass (entity counts and item totals unchanged),
159 hooks, 0 missing, 0 error lines, no Skein warnings; dimensions,
random ticks, entities and block entities all ran in parallel (entities
4.29x overlap). Core tests pass.
