# Forge 1.21.11 port — 2026-09-30

The Forge edition of Skein for Minecraft 1.21.11, built from `skein/1.21.11-neoforge` as it stood on
2026-09-30, after the Forge 1.21.1 port (`skein/1.21.1-forge`, see its `docs/port-1.21.1-forge-2026-09-30.md`),
whose findings carried over. Same features, same mixins, same `config/skein-common.toml` keys and
defaults, and the same `/skein` commands. Jar: `build/libs/skein-1.0.0-1.21.11-forge.jar`.

## Toolchain

| | |
| --- | --- |
| Forge | 1.21.11-61.2.1 (`loaderVersion [61,)`) |
| Build | ForgeGradle 7.0.40, Gradle 9.5.0, Java 21, official (Mojang) mappings, `eventbus-validator` 7.0.5 annotation processor |
| Runtime names | Mojang (checked in the Forge 61 server jar), so there is no refmap |
| Mixin | 0.8.7, shipped by Forge |
| MixinExtras | **shipped by Forge 61** (`mixinextras-forge` 0.5.4 in the server's libraries), so the jar bundles nothing; compiled against `mixinextras-common` 0.5.4 |
| Access transformer | `ChunkMap.getVisibleChunkIfPresent` made public, as on 1.21.1 |

## What changed against the NeoForge edition

Forge 1.21.6+ uses EventBus 7: every event has its own static `BUS`, mod-bus events are reached through
the mod's `BusGroup`, and the tick and lifecycle events are records.

| NeoForge | Forge 61 |
| --- | --- |
| `@Mod` constructor `(IEventBus, ModContainer)` | `(FMLJavaModLoadingContext)`, `context.getModBusGroup()` |
| `modBus.addListener(ModConfigEvent.Loading.class, …)` | `ModConfigEvent.Loading.getBus(modBus).addListener(…)` |
| `NeoForge.EVENT_BUS.addListener(ServerStartingEvent.class, …)` etc. | `ServerStartingEvent.BUS.addListener(…)`, and the same for stopping, stopped, `RegisterCommandsEvent` and `TickEvent.ServerTickEvent.Pre/Post` |
| `ServerTickEvent.Post#getServer()` | the record accessor `server()` |
| `ModConfigSpec` | `ForgeConfigSpec` (`defineListAllowEmpty` without the new-element supplier) |
| `EventHooks.fireLevelTickPre/Post` | `ForgeEventFactory.onPreLevelTick/onPostLevelTick` |
| `EventHooks.onExplosionDetonate(Level, ServerExplosion, List, List)` | `ForgeEventFactory.onExplosionDetonate(Level, Explosion, List<BlockPos>, List<Entity>, double)`. The inject point in `ServerExplosion.hurtEntities` is the same call, and the entity list is still the second `List` local |
| `CommonHooksMixin` on `CommonHooks.onPlaceItemIntoWorld` | `ForgeHooksMixin` on `ForgeHooks.onPlaceItemIntoWorld` |
| `mod file .getContents().containsFile(path)` | `Files.exists(modFile.findResource(path))` |

Removed because the thing they guard does not exist on Forge:

- **`Block.capturedDrops`** hooks in `BlockMixin` (and `TickContext.Local.drops`). Forge 61 has no
  drop-capture list either.
- **`SectionStorage.remove(ChunkPos)`**, a NeoForge addition.
- **`LivingEntityEffectsMixin` / `SkeinEffects`** and their check in `TrackingPass`. On NeoForge,
  1.21.11's `LivingEntity.updateSynchronizedMobEffectParticles` posts `EffectParticleModificationEvent`
  once per effect from inside `ServerEntity.sendChanges`, so an entity with a pending particle rebuild
  was kept on the level thread for mods' listeners. Forge's version of that method posts nothing. It
  only writes the entity's own synced data, so tracking such an entity on a worker is as safe as any other.

Added: **`ChunkMapUnsavedMixin`**, which serialises `ChunkMap.setChunkUnsaved`. It fixes a race that
the NeoForge 1.21.11 edition shares; see Soak below.

Forge-only fields on the hooked classes (field-by-field diff against the NeoForge sources):
`ServerLevel.capabilityData/partEntities`, `LevelChunk.capProvider`,
`Entity.canUpdate/captureDrops/hasExtraSpawnData/isAddedToWorld/persistentData`,
`LivingEntity.handlers`, `Mob.spawnReason`, `Block.renderProperties`. All are per-object. The only
cross-object one is `ServerLevel.partEntities`, which is written from the entity-section tracking
callbacks. Skein already defers those to the barrier (`EntitySectionCallbackMixin`), exactly like
vanilla's own `dragonParts` next to it.

## Verification

### Build

`./gradlew build`: compiles, `coreTest` passes.

### Boot and hook check

Real Forge 61.2.1 dedicated server from the official installer, jar in `mods/`, first boot:
`Skein: 164 hooks checked, 0 missing` and `16 worker threads; dimensions on, scheduled ticks off,
random ticks on, entities on, block entities on; 8-chunk cells, 32-block margin`. The Forge-only removals
were made before this boot, from the 1.21.1 findings and a source check. The 1.21.1 port showed the
checker catching real missing hooks on Forge's runtime.

### Soak

Same harness and world as the 1.21.1 Forge soak (see that port's report): a real Forge 61.2.1 server,
`-Xmx4G`, 16 workers, 1024 force-loaded overworld chunks plus 256 nether chunks on a glass deck above
the roof, four idle creative players (protocol 774 clients, which send `player_loaded`), 80 pens with
1200 cows and 400 sheep, 157 closed hopper rings with 15,072 items, and 320 parked item entities.
`randomTickSpeed 40`. `pause-when-empty-seconds=0` (1.21.2+ pauses an empty server). Counts are taken
per dimension. Note that 1.21.11 prints `Test passed. Count: N`, with a capital C.

**First run: a real race, now fixed.** Within a minute of the first soak, with default settings:

```
Skein Worker #10: Level ticker for minecraft:hopper@(-79,-60,-200) threw during parallel block entity tick
Caused by: ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 2049
  at LongLinkedOpenHashSet.add
  at ChunkMap.setChunkUnsaved
  at LevelChunk.markUnsaved
  at Level.blockEntityChanged
  at BlockEntity.setChanged
  at InvWrapper.insertItem <- VanillaInventoryCodeHooks.insertHook <- HopperBlockEntity.ejectItems
```

Ten seconds later the nether's `ChunkMap.saveChunksEagerly` threw from `LongLinkedOpenHashSet.fixPointers`,
and `crashPolicy = DISABLE` dropped the nether to serial ticking. Since 1.21.2, a chunk that changes for
the first time after a save adds itself to `ChunkMap.chunksToEagerlySave`, a plain
`LongLinkedOpenHashSet`, from whichever thread made the change. That includes every random-tick block
change and every `setChanged`. Skein did not guard it. The code is identical on NeoForge 1.21.11, so the
NeoForge edition has the same exposure; it was reported rather than fixed here, because this port does
not edit that tree. 1.21.1 has no such listener. The fix is `ChunkMapUnsavedMixin`: `setChunkUnsaved`
runs under a lock on the set. The only reader, `saveChunksEagerly`, runs in the level's chunk tick with no
pass open. The log of that run is kept locally as `run/evidence/soak-A1-eager-save-race.log`.

**With the fix**, two legs on a fresh world, with a restart between them:

| leg | config | length | random ticks | entities | block entities | overall (`/forge tps`) |
| --- | --- | --- | --- | --- | --- | --- |
| A, after building | defaults (`adaptive = true`, which chose parallel for all three) | 5 min | 4.5-6.1x, 1024-1280 chunks | 4.9-6.1x, ~1925 parallel / 4 serial | 1.4-4.6x, 484-612 parallel | 4.96-5.15 ms |
| B, after a restart | `adaptive = false` | 6.5 min | 3.9-4.6x, 1280 chunks | 5.2-6.1x, 1920 parallel / 4 serial (the players) | 2.3-4.0x, 612 parallel / 16 serial | 4.88-5.03 ms |

Every count is identical at the end of both legs, per dimension. The logs show zero `Exception`, zero
"refused to load chunk", zero "wrong thread", zero "threw during", zero quarantines and zero FATAL.
There is one "Can't keep up" (2.2 s), during the build, while 1280 chunks were being generated, before
any measurement. 11.5 minutes in all with the fix.


### Performance

BENCH_PLACEHOLDER

## Open risks

- **The eager-save race is fixed here, not in the NeoForge edition.** `skein/1.21.11-neoforge` has
  the same unguarded `ChunkMap.setChunkUnsaved`, and has been reported for a fix. The same check applies
  to any later version.
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

