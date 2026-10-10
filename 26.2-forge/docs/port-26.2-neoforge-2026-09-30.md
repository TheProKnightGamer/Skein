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

**10-minute soak** (`tools/soak.py`, 646 s wall). The world is superflat, with
512 force-loaded overworld chunks holding 64 fenced pens of 10 animals, wheat,
saplings and 60 closed four-hopper rings. The nether has a 14x17-chunk
force-loaded platform with 16 pens and 20 rings. `random_tick_speed 100`, mob
spawning off. The 800 animals are tagged and invulnerable, so counting them
tests Skein, not the animals' own deaths. The item count is taken under
`/tick freeze`, so no item moves while the rings are being read.

| check | start | end |
| --- | --- | --- |
| tagged animals, overworld | 640 | 640 |
| tagged animals, nether | 160 | 160 |
| items in hopper rings | 7680 | 7680 |
| exceptions / ERROR lines | | 0 |
| "refused to load chunk" / "wrong thread" | | 0 / 0 |
| "Can't keep up" | | 1, during the scripted build |

The loose items the dried-out farmland dropped at the start (643) had
despawned to 140 by the end, as vanilla despawning does after 5 minutes.

`/skein status` over the soak (samples 60 s apart):

| phase | overlap |
| --- | --- |
| dimensions | 1.35x - 1.93x |
| random ticks | 2.56x - 3.17x |
| entities | 1.88x - 2.32x |
| block entities | 1.14x while parallel. The adaptive governor sends the ~240 hoppers' 0.05 ms back to the serial path most of the time, because a barrier costs more than that. |

## Performance

See the bench section of the README, and `skein/bench/results/` for the raw
runs.
