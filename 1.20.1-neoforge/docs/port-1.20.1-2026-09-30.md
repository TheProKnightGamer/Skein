# Port to 1.20.1 (Forge 47.4.23 and NeoForge 47.1.106) — 2026-09-30

Source of truth: `skein/1.21.1-neoforge`. The two 1.20.1 folders share one
source tree (`src/` is identical; NeoForge 47.1 still uses the
`net.minecraftforge` packages and the `forge` mod id) and differ only in
`build.gradle` (dependency coordinates, repository, jar name) and
`gradle.properties` (loader version).

Decompiled Forge-patched 1.20.1 sources used throughout:
`mod_dev/.mc-1.20.1-sources` (from ForgeGradle's
`forge-1.20.1-47.4.22_mapped_official_1.20.1-sources.jar`).

## Toolchain

| | 1.21.1 NeoForge | 1.20.1 Forge / NeoForge |
| --- | --- | --- |
| Java | 21 | **17** |
| Build plugin | ModDevGradle 2.0.119 | ForgeGradle 6 + MixinGradle 0.7 (refmap) |
| Gradle | 9.2 | 8.8 |
| Runtime names | Mojang | **SRG** (`m_8490_`) — mixins go through `skein.refmap.json` |
| Mixin | 0.15 + MixinExtras (bundled) | 0.8.5, **MixinExtras 0.4.1 nested via jarJar** (neither loader bundles it) |
| Mixin configs | `[[mixins]]` in mods.toml | `MixinConfigs` manifest attribute |
| Config API | `ModConfigSpec` | `ForgeConfigSpec` (same keys, same file `config/skein-common.toml`) |

The shipped jar is the `jarJar` output (renamed `skein-1.0.0-1.20.1-forge.jar` /
`skein-1.0.0-1.20.1-neoforge.jar`), reobfuscated by `reobfJarJar`. Verified by
`javap`: calls and `@Shadow` fields in the shipped mixins are SRG
(`ServerLevel.m_183326_`, `Explosion.f_46012_`).

Compatibility mixins whose targets live in other mods (SpeeDeeEntity) sit in
their own `compat` source set: the Mixin annotation processor rejects a string
target it cannot see, and those targets need no refmap entries. They are
packed into the same jar; `SkeinMixinPlugin` still adds each only when its mod
is present.

## The hook checker under SRG names

`core/Hooks` checks that every injector landed by comparing the method names in
the mixin's annotations with the finished class. On 1.20.1 the annotation says
`tickChunks` and the class says `m_8490_`, so without help every hook reads as
missing and Skein switches itself off. `Hooks.resolve` now reads the same
`skein.refmap.json` Mixin reads and looks for the mapped name; it keeps the
written name where there is no entry (Forge's own methods, which are never
renamed) and in a dev run (where the refmap is remapped back to readable names
and the SRG form matches nothing).

## What changed, and why

Every mixin selector, `@At` target, `@Shadow`, `@Accessor` and `@WrapMethod`
signature was validated against the 1.20.1 jar with `tools/check_mixins.py`
before the first launch.

| 1.21.1 hook | 1.20.1 | Change |
| --- | --- | --- |
| `Block.capturedDrops` bracket (3 hooks) | NeoForge-only static; Forge 1.20.1 has none | removed — the hazard does not exist |
| `CommonHooks.onPlaceItemIntoWorld` snapshot bracket | `ForgeHooks.onPlaceItemIntoWorld` | retargeted (`remap = false`) |
| `Entity.changeDimension(DimensionTransition)` | `changeDimension(ServerLevel)` delegates to Forge's `changeDimension(ServerLevel, ITeleporter)` | hook the Forge overload (keeps its name at runtime, `remap = false`) |
| `Entity.handlePortal` / `portalProcess` | `handleNetherPortal` / `isInsidePortal` | retargeted |
| `changeDimension` overrides on ItemEntity, FallingBlockEntity, PrimedTnt, AbstractVillager | only ItemEntity and AbstractVillager override it | two targets |
| `ExplosionDamageCalculator.getEntityDamageAmount` | damage and knockback share one `getSeenPercent` call in `Explosion.explode` | mixin removed; the knockback wrap covers both |
| `EventHooks.onExplosionDetonate` | `ForgeEventFactory.onExplosionDetonate` | retargeted |
| `Util.shuffle(List, RandomSource)` | `Util.shuffle(ObjectArrayList, RandomSource)` (same algorithm) | descriptor |
| `PathTypeCache` (shared per-level table) | no shared table: each mob's `WalkNodeEvaluator` keeps its own `pathTypesByPosCache` | `PathTypeCacheMixin`, `WalkNodeEvaluatorInvoker`, `PathTypeEntry` removed |
| `ServerCommonPacketListenerImpl.send` flush batching | no `suspendFlushingOnServerTick` in 1.20.1; every send flushes | removed |
| `SectionStorage.remove(long)` | does not exist | lock wrapper removed |
| `StructureBlockEntity.placeStructure(ServerLevel)` | a powered LOAD block calls `loadStructure(ServerLevel, boolean)` | retargeted |
| `ServerLevel.gameEvent(Holder<GameEvent>, …)` | `gameEvent(GameEvent, …)` | handler signature |
| `RandomizableContainerBlockEntity.lootTable` `ResourceKey<LootTable>` | `ResourceLocation` | shadow type |
| `ChunkHolder.getChunkIfPresent`, `ChunkResult` | `getFutureIfPresentUnchecked(..).getNow(null)`, `Either` | `core/ChunkPublished` |
| `ChunkMap.getVisibleChunkIfPresent`, `ChunkHolder.currentlyLoading` public via NeoForge's AT | protected / package-private | `ChunkMapAccess` invoker, `ChunkHolderAccess` accessor |
| `addRegionTicket/removeRegionTicket(…, boolean)` | Forge-added overloads (not in SRG maps) | `remap = false` |
| `Level.addFreshBlockEntities` | Forge-added | `remap = false` |
| `ChunkMap.saveChunkIfNeeded` invoker + wraps in one mixin | the AP rejects two refmap entries for the same key | wraps name the full descriptor |
| `#minecraft:redirectable_projectile` | no such tag; the pickable projectiles are the classes that override `isPickable` | `SkeinConfig.redirectable` asks the class (SRG-aware) |
| `EntityDimensions.width()` | public field `width` | |
| Java 21 (`getFirst`, `Thread.ofPlatform`, `threadId`) | Java 17 | replaced |
| `IMixinConfig.getCleanSourceId` (Mixin 0.8.6+) | Mixin 0.8.5 | read reflectively, hint only |

### Lithium ports

Lithium itself has no Forge 1.20.1 build, but two ports do, and both carry all
nine conflicting option packages (checked in their jars): **Canary 0.3.3**
(`config/canary.properties`) and **Radium 0.12.4** (`config/lithium.properties`;
it also runs on NeoForge 47.1). Both ship `mixin.block.hopper` off by default,
so with defaults Skein reports eight conflicts, not nine.

`LithiumCompat` reads each port's own effective options, the way 1.21.1 reads
`LithiumConfig`:

- **Canary**: `com.abdelaziz.canary.common.config.CanaryConfig.load(./config/canary.properties)`,
  then `getEffectiveOptionForMixin` and `Option.isEnabled` -- the same calls
  Canary's plugin makes at startup.
- **Radium**: a fresh `me.jellysquid.mods.lithium.common.config.LithiumConfig`
  (its plugin class, which sits outside its mixin package and so can be loaded
  normally) asked through the public plugin API, `onLoad` then
  `shouldApplyMixin`, which also applies Radium's mod overrides.
- SuperChunk's relocated copy: its plugin, found through the mixins it applied
  (`Mixins.getMixinsForClass`).

The first attempt went through `Mixins.getConfigs()` and found nothing: Mixin
0.8.5's processor removes each config from that set as it selects it, so by the
time a server starts it is empty. Anything that cannot be read counts as on,
and then the log also says to set `general.ignoreModConflicts = true`, so it
never prescribes a fix that would not take effect.

Both ports also merge Lithium's `getLoadedExistingBlockEntity`, which returns
nothing on any thread but `Level.thread`, so a dimension ticking on a worker
would hide every inventory from its hoppers. `LithiumBlockEntityRetrievalMixin`
now targets that method (unprefixed, not remapped) and lets the level's owning
thread through, as on 1.21.1. It is an optional hook: with
`util.block_entity_retrieval` off the method does not exist. Lithium's
chunk-status tracker patch has no counterpart in either port and was dropped.

## Reflection by name

Three places looked methods up by their source names -- which block classes
override `entityInside` / `onProjectileHit` / `stepOn` / `fallOn` (the cluster
tier's hazard sets), whether a `ChunkHolder` overrides `broadcastChanges`, and
(new on 1.20.1) whether a projectile class overrides `isPickable`. In a real
server those names do not exist, the lookups fail closed, and the first launch
logged "could not find entityInside ... treating every block as one that reacts
to it": safe, but every block contact in the cluster tier would wait for the
barrier. `core/Srg` maps the SRG ids through Forge's own
`ObfuscationReflectionHelper.remapName`, which answers the SRG id in production
and the readable name in a dev run.

## Not ported, because 1.20.1 does not have the hazard

- NeoForge's static `Block.capturedDrops`.
- The shared per-level `PathTypeCache`.
- Packet flush batching (`suspendFlushingOnServerTick`).
- Lithium's chunk-status tracker (Canary/Radium have no equivalent).

## Known limit specific to Forge

`/forge track` (Forge's `TimeTracker`) keeps one "currently tracking" slot for
the whole server. While it is enabled and in-level phases run in parallel, its
per-entity timings are meaningless (the map itself is concurrent, so nothing
breaks). Profile with Skein's phases off.

## Verification

### Build

`./gradlew build` passes for both folders, including `coreTest`
("Core verification passed: entity precedence, point queries, component
ordering, grid safety, nested barriers").

### Boot (real dedicated servers, jar in `mods/`)

| | Forge 47.4.23 | NeoForge 47.1.106 |
| --- | --- | --- |
| hooks | `159 hooks checked, 0 missing` | `159 hooks checked, 0 missing` |
| phases | dimensions on, scheduled ticks off (default), random ticks on, entities on, block entities on | same |
| MixinExtras | 0.4.1 from the nested jar | same |

1.21.1 checks 169 hooks. The ten fewer are exactly the eight hooks removed
above plus two fewer `changeDimension` override targets.

**Deliberately broken hook.** A build with `ChunkLoopMixin`'s tick-chunk wrap
pointed at `ServerChunkCache.tick` (a real method that never calls
`tickChunk`) booted with `159 hooks checked, 1 missing; switched off: parallel
random ticks`, naming `ChunkLoopMixin.skein$collectRandomTicks in
ServerChunkCache.…m_201698_(…)` -- the SRG name, resolved through the refmap --
and every other phase still on. So the checker neither reports landed hooks as
missing under SRG names nor misses a real one.

### Soak

`tools/soak.py`: superflat, structures off, 1024 force-loaded overworld chunks
inside a barrier wall, 961 named mobs (cow, sheep, pig, chicken, zombie), 500
tagged invulnerable armour stands, 100 closed 4-hopper rings with 6400
cobblestone; a force-loaded Nether with 200 zombified piglins and 16 rings
(1024 cobblestone); nine headless players so chunks random-tick;
`randomTickSpeed 200`. Item totals are counted with every ring locked by a
redstone block, so the count is a snapshot. Death messages of the named mobs
are collected from the log.

A control leg with `general.enabled = false` found the only mob losses in the
earlier worlds were vanilla: superflat villages' iron golems killing zombies.
Structures are off in the soak worlds since.

Final soak, 11 minutes each, the release jars
(`skein-1.0.0-1.20.1-forge.jar` sha256 `4bd893c2…`,
`skein-1.0.0-1.20.1-neoforge.jar` sha256 `a914006e…`), 16 workers, one sample a
minute of `/skein status` and `forge tps`:

| | Forge 47.4.23 | NeoForge 47.1.106 |
| --- | --- | --- |
| verdict | **PASS** | **PASS** |
| items (snapshot, rings locked) | 7424 → 7424 | 7424 → 7424 |
| armour stands | 500 → 500 | 500 → 500 |
| every mob type (cow 193, sheep/pig/chicken/zombie 192, zombified piglin 201) | unchanged | unchanged |
| named-mob deaths | 0 | 0 |
| exceptions, "threw while", "refused to load chunk", missing hooks | 0 | 0 |
| mean tick, median of samples | 5.75 ms (5.65–9.43) | 5.93 ms (5.72–6.24) |
| random ticks overlap, median (min–max) | 8.56x (7.48–10.25) | 8.18x (7.37–8.43) |
| entities overlap | 9.13x (7.93–9.92) | 8.08x (6.12–9.95) |
| block entities overlap | 6.97x (5.82–8.32) | 5.79x (4.73–7.64) |
| dimension overlap (overworld + nether) | 1.35x (1.17–1.64) | 1.31x (1.15–1.59) |

With `general.enabled = false` the same Forge world (structures on, otherwise
identical) ran at 13.6–14.8 ms/tick against 6.1–7.0 ms with Skein. That is an
eyeball number from the soak harness, not the benchmark: see below.

At `randomTickSpeed 30` on bare grass the adaptive governor keeps random ticks
serial (it estimated 2.16 ms parallel against 0.83 ms serial), which is the
point of the governor. 200 gives the phase real work.

### Lithium ports, booted

`tools/compat_boots.sh`: four boots of the Forge build, each with the port's
default config and then with the nine options set to `false`:

| | default config | nine options off |
| --- | --- | --- |
| Canary 0.3.3 | in-level phases **blocked**; log lists the 8 options still on, for `config/canary.properties`; retrieval hook landed (160 hooks, 0 missing) | "Canary is installed and the features that conflict with in-level threading are all disabled in its config, so both are running"; every in-level phase on; retrieval hook absent as expected (1 optional not applied) |
| Radium 0.12.4 | blocked; 8 options for `config/lithium.properties`; hook landed | both running; phases on; 1 optional not applied |

### Scheduled ticks, parallel autosave, explosions

The phases that are off by default on 1.21.1 are off here too
(`scheduledTicks`, `saving`). Two extra legs on the Forge soak world turned
them on and, every minute, re-flooded 25 water-and-lava basins, lit 16 TNT
between the rings, and ran `save-all`:

- PASS both times: items 7424 → 7424, armour stands 500 → 500, 0 exceptions;
  the only mob deaths were "blew up".
- With basins 96 blocks apart (the phase links ticks within 80 blocks, two
  margins plus a chunk, and needs at least two components) and
  `minTicks = 1`, the logged summaries show the scheduled-tick phase running
  in parallel: up to **14.25x** (79.28 ms of ticks in 5.57 ms), 19-23
  components. At the default `minTicks = 64` the basins' bursts stay on
  vanilla's path, which is the threshold doing its job. With basins 48 blocks
  apart they chain into one component and vanilla runs them, as designed.


## Release jar (2026-10-02)

`build/release/skein-1.0.0-1.20.1-neoforge.jar` (sha256 `54b5a1108ae5…`) is the 2026-09-30 20:42 candidate built with the Canary/Radium config reader. Compared with the jar the cross-version benchmark measured (`bench/jars/1.20.1-neoforge-20260930.jar`), it changes exactly six classes, all in startup compatibility detection: `LithiumCompat` with its nested classes `Active`, `Conflict`, `Port` and `Reader`, plus `SkeinCompat`. Nothing in the tick path changed, so the benchmark results apply to it.

Its 11.5-minute soak (`build/soak-candidate.log`) conserved every item (7424 → 7424) and every tagged entity. The Forge soak reported `FAIL` on "10 exception lines", but all ten are the machine's TLS-intercepting proxy rejecting Mojang's auth-key fetch (`AuthenticationUnavailableException`, PKIX path building failed). That happens with or without Skein, and none of the lines came from Skein.
