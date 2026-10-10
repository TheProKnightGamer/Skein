# Porting Skein to Minecraft 26.2 (NeoForge) — 2026-09-30

This line is `skein/1.21.11-neoforge` carried to Minecraft 26.2 on NeoForge
26.2.0.88. Features, config keys, their meanings and the `/skein` commands are
the same as on 1.21.11. The only additions are one mixin for a hazard that
26.2 introduced, and the tooling in `tools/`. The Forge 26.2 line
(`skein/26.2-forge`) was made from this one; its own report covers the Forge
differences.

## Toolchain

| | 1.21.11 line | this line |
| --- | --- | --- |
| Minecraft | 1.21.11 | 26.2 (release, unobfuscated) |
| NeoForge | 21.11.42 | 26.2.0.88 (FML 11.0.16, Mixin 0.17.3+0.8.7, MixinExtras 0.5.4) |
| ModDevGradle | 2.0.141 | 2.0.147 |
| Gradle | 8.10.2 | 9.6.0 |
| Java toolchain | 21 | **25** (MC 26.x requires it) |
| Parchment | 1.21.11 | none. 26.x ships with real names, so there is nothing to map |

No refmap exists or is needed. The game ships unobfuscated, and NeoForge runs
Mojang's names in production too.

`pack.mcmeta` moved to the 1.21.9+ `min_format`/`max_format` form. It covers
resource format 88 through data format 107.1.

## What broke, and the fix

The compile errors were mechanical:

- **`ChunkPos` is a record.** `ChunkPos.asLong(x, z)` became `ChunkPos.pack`,
  `toLong()` became `pack()`, and `pos.x`/`pos.z` became the `x()`/`z()`
  accessors. The coreTest stand-in `ChunkPos` follows.
- **`Level.random` is `protected`.** Reads outside a mixin go through
  `getRandom()`. `LevelMixin` still swaps the field itself for the
  `SkeinRandom` wrapper.
- **`EntityType.is(TagKey)` is gone.** Checked through
  `builtInRegistryHolder().is(...)`.
- **`LoadingModList.get()` is deprecated for removal** (FML 11). Replaced by
  `FMLLoader.getCurrent().getLoadingModList()`.

Before the first launch, `tools/validate_mixins.py` checked every mixin
against the real 26.2 bytecode. It covers every `@Mixin` target, every
`@Shadow` field (owner and type, declared on the target class itself), every
`@Shadow` method, `@Accessor`/`@Invoker`, and every injector's `method =`. It
also checks every `INVOKE`/`FIELD`/`NEW` `target =` in the method bodies it is
aimed at, and every `@WrapMethod` handler's parameters and return type. The
checker was calibrated on known cases. It finds 0 problems in the 1.21.11 tree
against 1.21.11 bytecode, and 58 in the 1.21.1 tree against 1.21.11 bytecode
(the breakages the 1.21.11 port actually hit). On the copied tree against 26.2
it found exactly one:

- **`MinecraftServer.synchronizeTime(ServerLevel)` no longer exists.** 26.1
  replaced day time with a server-wide clock system (`ServerClockManager`). The
  server now broadcasts time once per tick, *before* the level loop
  (`forceGameTimeSynchronization`), so there is no per-level time sync left in
  the loop for Skein to replay. `DimensionTicker.tickLevels` lost its
  `timeSync` parameter, and `MinecraftServerMixin` lost the shadow.

A second script diffs, instruction by instruction, every vanilla method a
Skein injector targets, between 1.21.11 and 26.2 bytecode (146 methods). That
is how the one new hazard was found.

## New in 26.2, handled

- **Clock changes reach into every level** (`ServerClockManagerMixin`, new).
  Sleeping through the night runs inside the overworld's own tick. So does a
  `/time` command from a command block or a function. Either one ends in
  `ServerClockManager.modifyClock`, which clears the environment-attribute
  cache of *every* level. With dimensions ticking side by side, those levels
  are mid-tick on other threads. The calling level's own cache is still
  cleared at once. The others are cleared at the dimension barrier, which
  changes nothing they can observe: every level clears its own cache at the
  start of its next tick anyway (26.2 moved that clear to the top of
  `ServerLevel.tick`).
- **The eager-save queue** (`ChunkMapUnsavedMixin`, from the 1.21.x lines).
  Since 1.21.2, a chunk's first change after a save adds it to
  `ChunkMap.chunksToEagerlySave`, a plain `LongLinkedOpenHashSet`. The add
  comes from `LevelChunk.markUnsaved`, and so from every random-tick block
  change and every hopper `setChanged`. In a pass those adds come from several
  workers at once. The Forge 1.21.x port saw the set corrupt within a minute on
  a hopper world. 26.2's `setChunkUnsaved(ChunkPos)` is unchanged, and the adds
  are serialised on the set. The only reader, `saveChunksEagerly`, runs in the
  chunk tick with no pass open.
- **New content** (sulfur cubes, potent sulfur): the only new ticking logic
  with a reach, `PotentSulfurBlockEntity`, queries at most 2.5 blocks
  horizontally. That is well inside the 32-block margin.
- **New mutable statics** in `net.minecraft.world`/`server` (every static
  field that is non-final or holds a mutable collection, diffed against
  1.21.11): only `ServerLevel.ENTITY_COUNTER`, an `AtomicInteger`. Nothing new
  to guard.
- The scheduled-function queue (`/schedule`) moved from the overworld's level
  data to the server. As before, only the overworld's `tickTime` drains it, so
  nothing changes for Skein.

## Lithium

Lithium 0.25.3 exists for NeoForge 26.2 (there is none for Forge). Every
option in `LithiumCompat`'s conflict list still exists, under the same name
and with the same mixin class. The four compat mixins' targets are still
there: the chunk-status tracker, the block-entity lookup, the random block
ticking `getRandomBlockIndexForRandomTick` (which still reads and writes
`randValue`), and the POI storage extension. Options new since 0.21.4:
`mixin.profiler` is thread-safe (atomics, with a thread-local fallback).
`world.explosions.block_raycast.skip_air` sits inside the explosion ray loop
that Skein already yields to Lithium. The `ai.*` options are per-entity. None
of them needs adding to the conflict list. Runtime results are under
Verification.

## Verification

Dedicated server from the official installer, Java 25, `-Xmx4G`, run under
`box-run --heavy`. Skein config at its defaults (16 workers on this box).

**Boot.** `Skein: 169 hooks checked, 0 missing`. Dimensions, random ticks,
entities and block entities are on, and scheduled ticks are off (the default).

**10-minute soak** (`tools/soak.py`, 617 s wall). The world is superflat,
with 512 force-loaded overworld chunks holding 64 fenced pens of 10 animals,
wheat, saplings and 60 closed four-hopper rings. The nether has a
14x17-chunk force-loaded platform with 16 pens and 20 rings. On top of that
there is one more ring in every force-loaded chunk of both dimensions, so
many workers mark chunks unsaved at once. That makes 606 rings and 2424
hoppers. `random_tick_speed 100`, mob spawning off. The 800 animals are tagged
and invulnerable, so counting them tests Skein, not the animals' own deaths.
The item count is taken under `/tick freeze`, so no item moves while the rings
are being read.

| check | start | end |
| --- | --- | --- |
| tagged animals, overworld | 640 | 640 |
| tagged animals, nether | 160 | 160 |
| items in hopper rings | 41344 | 41344 |
| exceptions / ERROR lines | | 0 |
| "refused to load chunk" / "wrong thread" | | 0 / 0 |
| "Can't keep up" | | 1, during the scripted build |

The loose items the dried-out farmland dropped at the start (661) had
despawned to 151 by the end, as vanilla despawning does after 5 minutes.
An earlier 10-minute run with only the 80 build rings (7680 items) gave the
same result, before `ChunkMapUnsavedMixin` was added: everything conserved,
0 exceptions.

`/skein status` over the soak, ten samples 60 s apart:

| phase | overlap |
| --- | --- |
| dimensions | 1.40x - 1.93x |
| random ticks | 2.54x - 3.07x |
| entities | 1.80x - 2.43x |
| block entities | 1.44x - 2.86x (the adaptive governor ran it serially in 1 of 10 samples) |

`/neoforge tps` overall: 4.39 - 4.90 ms/tick.

## Performance

See the bench section of the README, and `skein/bench/results/` for the raw
runs.

### Lithium 0.25.3 at runtime (2026-10-02)

- **Lithium defaults** (`build/lithium-default.json`): Skein blocks the in-level phases and prints the nine options to add to `config/lithium.properties`. 176 hooks checked, 0 missing. One hook is left to Lithium: `ServerExplosion.calculateExplodedPositions` is also rewritten by Lithium's `collectBlocks`, so Skein turns off "explosion rays on the pool" and leaves that method to Lithium. Dimension threading runs.
- **Those options off** (`build/lithium-off.json`, `build/lithium-off.log`, a 6-minute soak on the release jar): "Lithium is installed and the features that conflict with in-level threading are all disabled in its config, so both are running". Random ticks, entities and block entities are on. 176 hooks, 0 missing, and one optional hook not applied (`LithiumBlockEntityRetrievalMixin`, whose target that option removes). Results: hopper items 41344 → 41344; tagged invulnerable animals 640 overworld + 160 nether, both exact; 0 exception lines, 0 refused chunk loads, 0 wrong-thread lines. The total entity count fell from 1440 to 837 only because loose item entities were pulled into the hoppers (640 → 37), which the conserved hopper total counts.
