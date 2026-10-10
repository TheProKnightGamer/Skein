# Skein on Forge 26.2 — 2026-09-30

This line is `skein/26.2-neoforge` with the loader layer swapped for Forge
65.1.3 (Minecraft 26.2). Everything in
[the NeoForge 26.2 port report](port-26.2-neoforge-2026-09-30.md) (a copy of the one in `skein/26.2-neoforge`) applies here too: the clock
change, `ServerClockManagerMixin`, `ChunkMapUnsavedMixin`, the toolchain and
the checks. This report covers only what differs on Forge.

## Toolchain

- **ForgeGradle 7** (`net.minecraftforge.gradle` `[7.0.17,8)`) with the
  mavenizer repository, **Gradle 9.5.0**, **Java 25**. This is the Forge 26.2
  MDK's own setup. `net.minecraftforge.gradle.merge-source-sets=true`, as in
  the MDK.
- **Mixin** 0.8.7 and **MixinExtras** 0.5.4 (`mixinextras-forge`) ship with
  Forge 26.2, so nothing is bundled into the jar. The mixin config is
  registered through the `MixinConfigs` manifest attribute.
- **No refmap.** Minecraft 26.x is unobfuscated and Forge runs Mojang's names,
  so the annotation processor has nothing to map.
- **EventBus 7.** Events are records with a static `BUS` per event type, and
  mod-bus events come from `FMLJavaModLoadingContext.getModBusGroup()`.

## Loader API mapping

| NeoForge 26.2 | Forge 26.2 |
| --- | --- |
| `@Mod` ctor `(IEventBus, ModContainer)` | `@Mod` ctor `(FMLJavaModLoadingContext)` |
| `container.registerConfig(COMMON, spec)` | `context.registerConfig(COMMON, spec)`, same file `config/skein-common.toml` |
| `ModConfigSpec` | `ForgeConfigSpec`. Every key, default and range is identical. `defineListAllowEmpty` has no new-element supplier on Forge. |
| `modBus.addListener(ModConfigEvent.Loading.class, …)` | `ModConfigEvent.Loading.getBus(modBusGroup).addListener(…)` |
| `NeoForge.EVENT_BUS.addListener(ServerStartingEvent.class, …)` | `ServerStartingEvent.BUS.addListener(…)`, and the same for stopping, stopped and `RegisterCommandsEvent` |
| `ServerTickEvent.Pre/Post` | `TickEvent.ServerTickEvent.Pre/Post` (`event.server()`) |
| `EventHooks.fireLevelTickPre/Post` | `ForgeEventFactory.onPreLevelTick/onPostLevelTick` |
| `CommonHooks.onPlaceItemIntoWorld` (snapshot capture writer) | `ForgeHooks.onPlaceItemIntoWorld`. The same three `Level` fields: `captureBlockSnapshots`, `restoringBlockSnapshots`, `capturedBlockSnapshots` |
| `EventHooks.onExplosionDetonate(Level, ServerExplosion, List, List)` | `ForgeEventFactory.onExplosionDetonate(Level, Explosion, List, List, double)`. The entity list is still local 1. |
| `net.neoforged.neoforge.entity.PartEntity` | `net.minecraftforge.entity.PartEntity` |
| `FMLLoader.getCurrent().getLoadingModList()` | `LoadingModList.getModFileById(...)` (static). The class check goes through `ModFile.findResource`. |
| `ModList.get().isLoaded(...)` | `ModList.isLoaded(...)` (static) |
| `ChunkMap.getVisibleChunkIfPresent` public (NeoForge AT) | protected on Forge. Reached through an `@Invoker` on `ChunkMapInvoker`. |

## What Forge does not have, and what went with it

`tools/validate_mixins.py` was run against ForgeGradle's recompiled
Forge-patched game jar plus the Forge universal jar. It found five problems,
all of the same kind: NeoForge-only code that Skein hooks.

- **`Block.capturedDrops` and `begin/stopCapturingDrops`.** NeoForge routes
  block drops through a static capture list, and Skein makes it per thread.
  Forge has no such list, so the three handlers that take it over are gone.
  `BlockMixin` keeps the `restoringBlockSnapshots` reads, which Forge does
  have.
- **`SectionStorage.remove(ChunkPos)`.** A NeoForge addition. Forge has no
  such method, so there is nothing to lock. The `@WrapMethod` is gone, and the
  rest of the POI lock is unchanged.

After that the checker reports 0 problems (298 checks).

A bytecode diff of every hooked method, Forge-patched against
NeoForge-patched, found nothing else that needs a hook. Forge fires its own
events at the same places (level tick, neighbour notify, projectile impact,
game events, entity section moves). Its sleep handling reaches
`ServerClockManager.setTotalTicks`, which goes through the same
`modifyClock` that `ServerClockManagerMixin` covers. Forge's
`removeErroringEntities`/`removeErroringBlockEntities` read Forge's own
config, in the same place NeoForge reads its own.

## Lithium

No Lithium build exists for Forge 26.2. The compat code only does anything
when a Lithium is installed, so here it is inert.

## Verification

Dedicated server from the official Forge installer, Java 25, `-Xmx4G`, run
under `box-run --heavy`. The 10-minute soak is the one described in the
NeoForge 26.2 report. On top of it there is one closed hopper ring in every
force-loaded chunk of both dimensions, so that many workers mark chunks
unsaved at once. Results are below.

**Boot.** `Skein: 166 hooks checked, 0 missing`. That is the NeoForge
build's 170 minus the four NeoForge-only hooks dropped above. Dimensions,
random ticks, entities and block entities are on, and scheduled ticks are off
(the default).

**10-minute soak** (617 s wall). Same world as NeoForge, plus 526 more
hopper rings, one per force-loaded chunk (606 rings in all):

| check | start | end |
| --- | --- | --- |
| tagged invulnerable animals, overworld | 640 | 640 |
| tagged invulnerable animals, nether | 160 | 160 |
| items in hopper rings (counted under `/tick freeze`) | 41344 | 41344 |
| exceptions / ERROR lines | | 0 |
| "refused to load chunk" / "wrong thread" | | 0 / 0 |
| "Can't keep up" | | 1, during the scripted build |

`/skein status`, ten samples 60 s apart:

| phase | overlap |
| --- | --- |
| dimensions | 1.42x - 1.98x |
| random ticks | 2.34x - 3.14x |
| entities | 1.97x - 2.53x |
| block entities | 1.71x - 2.42x (2424 hoppers; the adaptive governor ran it serially in 2 of 10 samples) |

`/forge tps` overall: 4.60 - 4.94 ms/tick.
