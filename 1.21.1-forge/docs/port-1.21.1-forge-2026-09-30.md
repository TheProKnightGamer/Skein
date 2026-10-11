# Forge 1.21.1 port — 2026-09-30

The Forge edition of Skein for Minecraft 1.21.1, built from `skein/1.21.1-neoforge` as it stood on
2026-09-30. Same features, same mixins, same `config/skein-common.toml` keys and defaults, and the same
`/skein` commands. Jar: `build/libs/skein-1.0.0-1.21.1-forge.jar`.

## Toolchain

| | |
| --- | --- |
| Forge | 1.21.1-52.1.16 (`loaderVersion [52,)`) |
| Build | ForgeGradle 7.0.40, Gradle 9.3.1, Java 21, official (Mojang) mappings |
| Runtime names | Mojang. The Forge 52 server jar has `ServerLevel.tickChunk` etc. by name, so the mixins need no refmap and `core/Hooks` compares names as written. |
| Mixin | 0.8.7, shipped by Forge; the config is named by `MixinConfigs` in the jar manifest |
| MixinExtras | **not** shipped by Forge 52. Bundled as jar-in-jar: `META-INF/jarjar/mixinextras-forge-0.5.0.jar`, range `[0.5.0,)`, so Forge picks the newest when several mods bundle it |
| Access transformer | `META-INF/accesstransformer.cfg` makes `ChunkMap.getVisibleChunkIfPresent` public. NeoForge's own AT already does; Forge's does not |

FG7's `accessTransformer = true` magic flag picks the AT from the *last* source set that has one, which
here was `coreTest`, so the file is named explicitly with `accessTransformers = files(...)`.

## What changed against the NeoForge edition

Loader API, one-for-one:

| NeoForge | Forge |
| --- | --- |
| `@Mod` constructor `(IEventBus, ModContainer)` | `(FMLJavaModLoadingContext)`; config registered on the context |
| `NeoForge.EVENT_BUS.addListener(Class, …)` | `MinecraftForge.EVENT_BUS.addListener(…)` (typed by the listener) |
| `ServerTickEvent.Pre/Post` | `TickEvent.ServerTickEvent.Pre/Post` |
| `ModConfigSpec` | `ForgeConfigSpec`; `defineListAllowEmpty` has no new-element supplier argument |
| `EventHooks.fireLevelTickPre/Post` | `ForgeEventFactory.onPreLevelTick/onPostLevelTick` |
| `EventHooks.onExplosionDetonate` (inject point in `Explosion.explode`) | `ForgeEventFactory.onExplosionDetonate`, same descriptor |
| `CommonHooks.onPlaceItemIntoWorld` (`CommonHooksMixin`) | `ForgeHooks.onPlaceItemIntoWorld` (`ForgeHooksMixin`); same three field accesses |
| `neoforge.entity.PartEntity`, `neoforge.common.util.BlockSnapshot` | the `net.minecraftforge` classes of the same name |
| `LoadingModList`, `ModList` (`net.neoforged.fml`) | the `net.minecraftforge.fml` classes |

Hooks that do not exist on Forge, found by the first boot (see below) and removed:

- **`Block.capturedDrops`** (three injectors in `BlockMixin`). NeoForge collects a broken block's drops in
  a `static` list for its `BlockDropsEvent`; Forge 52 has no such list, and `popResource` spawns each drop
  directly. There is nothing shared to make per-thread, so the per-thread copy (`TickContext.Local.drops`)
  went with them.
- **`SectionStorage.remove(long)`**. A NeoForge addition (it drops POI data for an unloaded chunk). Forge
  never removes POI sections that way, so there is no extra writer to lock.

Metadata: `mods.toml` with `displayTest = "IGNORE_ALL_VERSION"` (vanilla clients and clients without
Skein can join; Skein registers no network channel), `logoFile = "skein_logo.png"`, `logoBlur = false`,
licence as `LICENSE_skein`.

### Forge-only state on the hooked tick paths

A port can break silently when the other loader adds shared state of its own to a method Skein runs on
a worker. Every class Skein hooks, plus `FarmBlock`, `HopperBlockEntity`, `LivingEntity` and `Mob`, was
diffed field by field against the NeoForge sources. The only Forge-only fields are `ServerLevel.capabilityData`,
`LevelChunk.capProvider`, `Entity.canUpdate/hasExtraSpawnData/isAddedToWorld`, `LivingEntity.handlers`,
`ServerPlayer.PERSISTED_NBT_TAG` and `Block.renderProperties`. All are per-object, and none is written
from another object's tick. Forge's `static` collections in `net.minecraftforge.common` were checked too.
The one on a tick path, `FarmlandWaterManager.customWaterHandler` (read from farmland random ticks), is
the same `WeakHashMap` NeoForge has, read-only during ticks. The entity loop in `ServerLevel.tick` has
the same shape on both loaders (Forge returns early for `PartEntity`, where NeoForge wraps the call in an
`if`). `canUpdate()` is checked inside `tickNonPassenger`, which Skein still calls.

## Verification

### Build

`./gradlew build`: compiles, `coreTest` passes ("Core verification passed: entity precedence, point
queries, component ordering, grid safety, nested barriers.").

### Boot and hook check

Real Forge 52.1.16 dedicated server from the official installer, with the jar in `mods/`.

- **First boot, before the removals above:** `Skein: 169 hooks checked, 4 missing, 1 optional not applied;
  switched off: all of Skein, every in-level feature`, naming `BlockMixin.skein$readCapturedDrops in
  Block.stopCapturingDrops`, `skein$writeCapturedDrops` in `beginCapturingDrops`/`stopCapturingDrops`,
  and `SectionStorageMixin.skein$lockRemove in SectionStorage.remove(J)V`. `/skein status` said
  `state: disabled`. This proves `core/Hooks` works on Forge's runtime: it found real missing hooks by
  name and switched the mod off instead of running half a bracket.
- **After:** `Skein: 165 hooks checked, 0 missing` and `16 worker threads; dimensions on, scheduled ticks
  off, random ticks on, entities on, block entities on; 8-chunk cells, 32-block margin`, which is the
  NeoForge default set.

### Soak

Real Forge 52.1.16 dedicated server, `-Xmx4G`, default Skein config (16 workers) except where noted, run
under the box's `--heavy` lock in two legs with a restart between them (the world saved and reloaded
under Skein).

World: superflat, 1024 force-loaded overworld chunks (x and z from -256 to 255), 256 force-loaded nether
chunks above the bedrock roof. Four idle creative players (headless protocol-767 clients) stand at
(±128, ±128), so the area random-ticks the way a played server does. Overworld: 64 glass pens of 15 cows
and 5 sheep, 121 closed 2x2 hopper rings with 96 cobblestone circulating in each, and 256 parked item
entities (no despawn, no pickup, four types per pen so nothing merges). Nether: 16 pens, 36 rings, and
64 parked items. `randomTickSpeed 40`, mob spawning and daylight off. Every leg ends by counting cows,
sheep, item entities by type and exact stack size, and every item in every hopper, per dimension.

| leg | config | length | random ticks | entities | block entities | overall (`/forge tps`) |
| --- | --- | --- | --- | --- | --- | --- |
| A, after building | defaults (`adaptive = true`) | 5 min | serial by choice, 2.08x when it went parallel | 5.6-7.1x, ~1850 parallel / 30-50 serial | serial, then 1.4-1.6x | 5.0-5.9 ms |
| B, after a restart | `adaptive = false`, every phase forced parallel | 6.5 min | 4.4-5.7x, 832 chunks | 5.5-6.0x, ~1790 parallel / 17-32 serial (the players) | 2.8-3.7x, 612 parallel / 16 serial | 4.8-5.9 ms |

Overlap is each phase's CPU time over its wall time, from `/skein status` once a minute. The `serial`
entities are the four players plus whatever sat near unloaded chunks, which `loadedMarginChunks` pins.

Result: every count identical at the end of both legs, per dimension (overworld 960 cows, 320 sheep,
64 of each parked item, 11,616 hopper items; nether 240 / 80 / 16 / 3,456). The server log has zero
`Exception`, zero "refused to load chunk", zero "wrong thread", zero "threw during", zero quarantines,
zero FATAL, and no "Can't keep up" in leg B. 11.5 minutes in all, plus a 2-minute run of leg B that my
RCON client cut short (see the RCON note below), which was also clean.

Two harness problems found on the way, neither in Skein:
- Forge patches `RconClient.sendCmdResponse` to loop over the reply's bytes, so a command with **no**
  output (`gamemode creative` for a player already in creative) gets no reply packet at all. Vanilla
  and NeoForge send an empty one. A client that waits for the reply hangs.
- Vanilla RCON drops the connection on a very long reply (a scoreboard sum printing one line per
  entity). The counts above use `execute if entity` with exact NBT instead.

The 1.21.1 soak world's nether deck (`fill` of 256x256) was over the 32,768-block command limit and was
not placed, so its pens stand on the bedrock roof. That changes nothing above, but the 1.21.11 run used
four quarter fills.


### Performance

BENCH_PLACEHOLDER

## Open risks

- **SpeeDeeEntity compat on Forge is not exercised.** Skein's detection works (it logged
  `SpeeDeeEntity found; per-thread clearance caches and a shared per-tick collider index`), and Forge's
  jar-in-jar picked SpeeDee's bundled MixinExtras 0.5.3 over Skein's 0.5.0, as it should. But
  SpeeDeeEntity's own Forge 1.21.x jars ship SRG refmaps (`m_6933_` and so on) and fail to load on
  Forge's Mojang-named runtime, with or without Skein (`Critical injection failure` in its
  `LevelCacheMixin`). The compat mixins need a fixed SpeeDeeEntity build to be tested.
- **Lithium has no Forge release.** The Lithium compatibility code is inert on Forge, and no Forge
  port of Lithium was tested.
- **Forge events and capabilities on worker threads.** The same class of risk as on NeoForge: a mod's
  listener for an entity or block-entity event is called from a worker during a parallel pass. Forge
  adds lazily-attached capabilities (`AttachCapabilitiesEvent` fires on first access) and
  `VanillaInventoryCodeHooks`, which reach a neighbouring block entity from a hopper's tick. The hopper
  rings in the soak exercise that path, and every item was conserved. A third-party capability
  provider that keeps unsynchronised shared state is the case this cannot rule out.
- **Integrated server (singleplayer) not run.** Only the dedicated server was tested. The client run
  (`./gradlew runClient`) exists but needs a display, and was not used in this port.

