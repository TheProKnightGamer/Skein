# Lithium, SuperChunk, carpet and WorldEdit without configuration -- 2026-10-05

A client instance (Lithium 0.15.4, SuperChunk 0.4.0, carpet 1.0.8, WorldEdit
7.3.8) had been running Skein with every in-level phase off since 2026-10-02.
A spread-out snowball strip there -- 2,190 snowballs over 76 columns between
49 snow golems and 36 zombies -- ticked entirely on the server thread, 2 to 10
seconds behind. Four separate problems, all fixed here.

## What was wrong

1. **Lithium.** Skein's guard found eight of the nine conflicting Lithium options
   on and switched the in-level phases off, as designed, and printed the lines
   to add. Nothing switched them off automatically: the SuperChunk 0.4.0 jar in
   the instance (built 2026-09-29) never logged "Skein detected" and wrote only
   its own five Lithium rules.
2. **SuperChunk lost Skein after the rename.** Its bridge looks Skein up by
   package, and knew only the older package roots.
   With `com.theproknightgamer.skein` installed it found nothing, so its async
   catcher on `ServerChunkCache.tick` treated Skein's dimension workers as
   foreign: `ConcurrentModificationException: Async ticking server chunk
   manager`, and the nether and the end were quarantined on their first tick.
3. **Carpet.** `Explosion_optimizedTntMixin.noBlockCalcsWithNoBLockDamage`
   redirects the ray loop's `getBlockState`. Skein's hook check saw another
   mod's code in the stretch it replaces and switched off explosion rays on the
   pool. Worse, the exported `Explosion` class showed carpet's redirect is the
   `original` Skein's held-chunk wrapper is handed, and that wrapper's fast path
   skipped `original`: with `explosionNoBlockDamage` on, explosions still broke
   blocks.
4. **WorldEdit.** `MixinLevelChunkSetBlockHook` adds
   `setBlockState(BlockPos, BlockState, boolean, boolean)`, which sets a flag and
   calls vanilla's. Skein's three block-change hooks named `setBlockState`
   without a descriptor, leaving the overload they land on to mixin order.

## What changed

- **Skein's `neoforge.mods.toml` declares a `lithium:options` table** turning the
  nine options off. Lithium 0.15.4, 0.21.4 and 0.25.3 (NeoForge) and SuperChunk's
  bundled copies (NeoForge 1.21.1, Forge 1.20.1) look up that table from every mod
  before selecting mixins. Radium (Forge 1.20.1) reads the same key from the mod's
  own `[[mods]]` entry instead, so the 1.20.1 lines declare it there as well
  (2026-10-06). Lithium applies mod overrides before
  `lithium.properties`, so a user's explicit `true` still wins; the existing guard
  reads the startup config and turns the phases off in that case, and its message
  now says the option was turned back on. All eight lines carry the table.
- **SuperChunk** (`neo-1.21.1`, uncommitted): `SkeinCompat.PACKAGE_ROOTS`,
  `MixinSkeinConfig` and `MixinSkeinCommands` know `com.theproknightgamer.skein`
  first; `SkeinCompatTest` binds the renamed package. The tree's existing
  uncommitted Voxy change was left as it was.
- **Carpet** (`CarpetCompat`, 1.21.1-neoforge only; carpet has no other NeoForge
  build here): the hook check tolerates carpet's redirect in the ray stretch, the
  ray cast is skipped while `explosionNoBlockDamage` is on, and the held-chunk
  read goes through `original` while it is on.
- **Descriptors**: `LevelChunkMixin` and `SpeeDeeBlockChangeMixin` name
  vanilla's `setBlockState` by full descriptor on all eight lines (`Z` flag on
  1.20.1/1.21.1, `I` on 1.21.11/26.2). The 1.20.1 refmaps map both to
  `m_6978_`.
- **Tests**: `CarpetVerification` (runs when carpet is present); the explosion
  finalize trials accept a `LegacyRandomSource` subclass, which SuperChunk's
  `CheckedThreadLocalRandom` is and `BulkRandom` already leaves to its own draws;
  `SuperChunkVerification.coldChunkReads` now expects cell tasks to be refused a
  cold chunk, the policy since the 2026-09-24 round, rather than to load one;
  and the chunk-scan oracle asks the loop's `getTickingChunk` call as it was
  redirected. SuperChunk's C2ME `notickvd` redirect also lists FULL chunks with
  changes still to send, Skein's walk already ran that redirect (555 holders
  against the bare call's 424), and the oracle had been asking the bare call.
  Neither check could pass against Skein since the 2026-09-24 round.

## Verification

Each leg is a dedicated server running every in-game suite, NeoForge 21.1.219.

| Leg | Mods | Result |
| --- | --- | --- |
| A | Lithium 0.15.4, no `lithium.properties`; carpet; SpeeDeeEntity | pass. Lithium: "9 override(s) found". Skein: both running, 174 hooks, 0 missing, nothing left to carpet. Carpet: 179 blocks cast on the pool equal to the serial loop with the rule off; 0 broken with it on |
| A + WorldEdit | as A, plus WorldEdit 7.3.8 | no ambiguity warning, 174 hooks, 0 missing; every suite before tracking passed. The tracking suite's fake player joins before the first tick, and WorldEdit's online-player set is immutable until then, so WorldEdit throws in its own constructor -- a harness artefact, not reachable by a real login |
| B | patched SuperChunk (bundled Lithium); carpet; SpeeDeeEntity; SuperChunk suite, scheduled ticks and saving requested | pass. All four in-level phases ran in parallel (scheduled 897, random 11,870, entities 28,160, block entities 32,330); SuperChunk pinned only parallel saving; 288 hopper pairs conserved, leaves decayed and updated, the policy survived a reload; 106 chunk walks matched; no quarantine, 0 errors |
| C | patched SuperChunk with standalone Lithium; carpet; SpeeDeeEntity; SuperChunk suite | pass, as B (scheduled 1,066, random 11,312, entities 25,600, block entities 29,094). Lithium: "13 override(s) found". The one ERROR line is C2ME worldgen's `Unprimed heightmap` |

On 2026-10-06 the benchmark harness's `mixed` scenario with nine bot players also passed on this line (correctness checks, 169 hooks, 0 missing, 0 errors, every phase parallel). Core tests pass on 1.21.1-neoforge, and all eight lines build with the table in their jars. Only 1.21.1-neoforge was run in game; the other seven are built, not tested.

Not covered: Canary on 1.20.1 (no jar to check whether it reads the table; the
guard still prints its lines), and a live run of the client world itself.
