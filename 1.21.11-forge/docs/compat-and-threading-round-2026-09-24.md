# Compatibility and threading round — 2026-09-24

Two aims. First, Skein should survive other mods touching the code it hooks:
not crash the pack at startup, not run half a mechanism, and not skip another
mod's change for the work it takes over. Second, it should fix the concurrency
defects a three-part review found. Both lines, 1.21.1 and 1.21.11, carry the
same changes, adapted to each version's API.

Copies of both trees before this round:
`skein/backups/skein-src-before-perfect-2026-09-24-1119.tar.gz` and
`skein-1.21.11-src-before-perfect-2026-09-24-1119.tar.gz`. The 1.21.1 jar from
before is next to them.

## Other mods

**No more `@Redirect`.** All 35 were `@Redirect`s, and a `@Redirect` fights
any other mod's `@Redirect` of the same call: Mixin applies the higher
priority and drops the other, and at Skein's 1500 the other mod was the one
dropped. Each is now a `@WrapOperation`, which chains with other wrappers and
wraps another mod's redirect instead of replacing it. Where Skein used to do
the vanilla action itself, it now calls the original, so another mod's
wrappers still run when Skein is not taking the call over.

**Per-thread copies only while a level is ticking.** The redstone wire's
`shouldSignal` was per thread unconditionally; it now follows `BlockMixin`'s
rule: per thread while the thread is ticking a level, and the real field
otherwise. So a mod that sets the field outside a tick sees its own write.

**Loop hooks sit inside other mods' wrappers.** The hooks that collect the
entity loop, the block-entity tickers and the random-tick chunks moved into
`EntityLoopMixin`, `BlockEntityLoopMixin` and `ChunkLoopMixin` at priority
900. MixinExtras nests wrappers of one call by priority, lowest innermost. A
profiler or throttle wrapping `TickingBlockEntity.tick()` now stays outside
Skein's wrapper. It runs on the level thread, in loop order, for every ticker,
and it decides whether a ticker runs before Skein files it. Before, Skein
filed the ticker and ran it later on a worker, around the other mod's
wrapper.

**A missing hook costs its feature, not the game** (`core/Hooks`,
`core/HookFor`). Every injector is applied with `require = 0`. A Mixin
extension, registered behind MixinExtras's own so that it sees the finished
class, checks each one: for every method an injector names, the merged
handler must be called from that method. A `@WrapMethod` original counts
under its moved name. This is stricter than `require = 1`, which only counted
injections across all the methods an injector names. It found a hook that
had never applied (below).

The check reads each mixin from its own class file. Mixin's copy of the class
is not the compiled one: MixinExtras rewrites a handler that takes `@Local` or
`@Share` into a wrapper annotation, and the first version of the check, reading
that copy, skipped those handlers without a word -- seven on each line, including
the projectile queries' section hooks and 1.21.11's parallel explosion
exposure. The mixins added for other mods (Lithium, SpeeDeeEntity) are added by
the config plugin after Mixin has asked it for its targets, so their targets
are now named to the check separately and loaded before the first tick like
the rest.

A hook that did not land switches off the features it names:
- `CORE` means all of Skein; that is the default for an unannotated hook.
- `IN_LEVEL` means every in-level phase.
- A narrower feature, such as `EXPLOSIONS`, `ENTITY_TRACKING`,
  `PARALLEL_SAVING` or `RANDOM_TICKS`, switches off only itself.

The log names the hook, the method, and the other mixin configs that changed
the class. Paired hooks share a feature, so they switch off together. The few
pairs that act even with no phase open read the feature before acting and
fall back to vanilla together: the path-type cache, and SpeeDeeEntity's cache
and collider hooks. Every target class is loaded before the first tick, so
all of this is decided before anything runs.

**Priority 1500.** The config's `"priority"` only orders configs; the
mixins themselves ran at the default 1000 -- except the few that set 1500
themselves. Mixin refuses outright (a hard error, whatever `require` says) to
inject into a method merged by a mixin of equal or higher priority, so another
mod at 1000 overwriting almost any hooked method stopped the game. The
deliberate-conflict run found this. `"mixinPriority": 1500` puts every Skein
mixin above the default, so such an overwrite becomes a hook that goes
missing and is handled. The three loop mixins stay at 900 for the nesting
above, so a mod overwriting one of those whole loop methods is still a hard
error, as is a mod at 1500 or above overwriting any hooked method.

**No exclusive injectors.** A `@ModifyConstant`, like a `@Redirect`, can be
held by one mod only: Mixin gives the constant to the higher priority and
skips the other, and a skipped required injection is a crash. Raising Skein to
1500 made its ray-loop `@ModifyConstant` win over Lithium 0.21's
`block_raycast`, which targets the same constant, and the 1.21.11 PvP-setup run
crashed on it. Both lines now use `@ModifyExpressionValue` on the constant,
which chains: Skein sees the bound as any other mod left it, and a bound that
is no longer vanilla's 16 means another mod owns the loop, so Skein casts
nothing. On 1.21.1 the cast moved into that hook as well, as on 1.21.11, so a
loop another mod owns costs no random draws.

**Yielding.** Two features take a stretch of vanilla code over: pooled entity
tracking runs `sendChanges` off the loop, and pooled explosion rays replace
the ray loop. If another mod's injector lands inside that stretch, or another
mod overwrote the method, the feature is switched off (`@HookFor(yieldsUntil =
..., yields = ...)`). The other mod's change then still applies to every
entity and every ray. Lithium's `block_raycast` is such a case: its
`@ModifyConstant` runs first and leaves Skein a bound of 0, so Skein casts
nothing, and on 1.21.11 its `collectBlocks` callback also sits inside the
stretch, so the log says the ray loop is left to Lithium and
`/skein status` counts one hook left to another mod. The stretch follows
MixinExtras bridges, so another mod's wrapper nested inside one of Skein's is
seen, and when the end of the stretch sits in a loop that began inside it,
the stretch runs to the end of that loop's body.

**Optional hooks for the PvP bot.** Its herobot `BlockMixin` overwrites
`Block.popResource`. The capture-list read and the restoring-flag read there
are now separate, optional hooks, because an overwrite that never reads them
leaves nothing to protect. Before this, the per-method check would have
switched off all of Skein on a test server.

## Defects fixed

From the review (three reviewers: core scheduling, per-thread state, newest
features), each checked against the vanilla source:

- **1.21.1 loaded chunks on workers.** A parallel tick that missed the chunk
  cache ran the chunk system's main-thread work on the worker. That work
  includes other chunks' FULL promotions, which add their entities, tick
  containers and trackers, and it ran while every other worker read the same
  structures without a lock. This is the failure behind 1.21.11's 2026-09-09
  crash. Changes, ported from 1.21.11:
  - loads are refused during a pass;
  - `entities.loadedMarginChunks` pins entities near the loaded edge to the
    level thread;
  - player chunk-tracking writes (`move`, `addEntity`, `removeEntity`) are
    parked;
  - `getChunkFuture` hands back already-loaded chunks instead of refusing them
    (both lines).
- **Ticks that reach into another dimension.**
  - An ender pearl landing in one dimension teleported and hurt its thrower
    in another, from the pearl's tick. The whole landing is now parked (1.21.1
    `ThrownEnderpearl.onHit`; 1.21.11 the same).
  - Every dimension change and teleport made from a level the caller is not
    ticking is parked past the dimension barrier: `changeDimension`/
    `teleportTo` on 1.21.1, `teleport(TeleportTransition)` on 1.21.11.
  - Spawns into another level are parked the same way.
  - A chunk request into another dimension used to hang (vanilla's off-thread
    path waits for a thread that is busy ticking) or race (on the server
    thread). It now fails once, is logged, and the asking dimension ticks
    after the others from then on.
  - The server thread is no longer treated as the owner of a level it is not
    ticking.
- **Neighbour updates on the shared updater.** The per-thread neighbour
  updater was selected by bare method names, and a bare name hooks only the
  first overload. `ServerLevel.neighborChanged`'s second overload -- on both
  lines -- and, on 1.21.11, the `updateNeighborsAt` that actually does the
  work were never hooked, so two cells in one level could push update chains
  through the same `CollectingNeighborUpdater`. The old `require = 1` counted
  the other methods and passed; the per-method check found the 1.21.11 case,
  and an audit of every bare selector against the compiled classes found the
  rest. All four methods now name their descriptors, as does 1.21.11's
  `ServerPlayer.teleport`, which javac pairs with a bridge method. The check
  now also warns at startup if a bare selector names an overloaded method.
- **Removal replay.** A parked removal's replay set the entity's level
  callback to `NULL`, clobbering the callback of a level the entity had
  joined in the meantime.
- **Per-thread brackets after a swallowed exception.** A mod exception inside
  NeoForge's placement capture or the wire bracket left that worker capturing
  for good: every later `setBlock` on that thread skipped client updates and
  neighbour updates. Brackets are now reset after every logged task failure
  and whenever a thread leaves level work. The server thread's state is
  replaced per server, so nothing carries from one singleplayer world to the
  next.
- **The pool helped with other levels' work.** A coordinator waiting at its
  own barrier could pick up another level's cells, or a whole dimension tick,
  and run it nested inside its own pass. It now runs only its own batch and
  takes its unclaimed runners back off the queue.
- **`/skein reload`** rebuilt the blacklists on whatever thread ran the
  command, including mid-phase from a command block. It now marks the config
  stale for the next tick.
- **Quarantine** now also takes a crashing level's in-level phases off the
  pool.
- **Explosions.**
  - Rays and exposure on the pool are cast only if every chunk they can reach
    is loaded; otherwise the explosion uses vanilla's loop. On 1.21.11 a TNT
    of `explosion_power` 128 crashed the server.
  - Creepers, fireballs and (on 1.21.11) TNT whose blast can reach past the
    loaded margin tick on the level thread.
  - Bulk random draws are taken only from a source no other thread can draw
    from.
  - The block epoch is atomic.
- **Cluster tier.**
  - A projectile whose path reaches a chunk that is loaded but not
    entity-ticking goes to the grid, because entities there are hittable but
    not in the tick list.
  - Entities that can merge get a reach from their speed, and very fast ones
    go to the grid.
- **Small ones.**
  - `HazardWatch` is synchronised; several levels' coordinators write it.
  - The raid leader is set under the raid lock, including `Raider.pickUpItem`'s
    check-then-set.
  - A villager about to level up ticks on the level thread, because a
    cartographer's new trades search for a structure up to a hundred chunks
    away.
  - Parallel autosave declines while a profiler is running (1.21.1).
  - 1.21.1's `popExperience` restoring-flag hook had never applied: the field
    is reached through `ServerLevel`, and `require = 1` passed on the other
    method.
  - The config is applied when it first loads, not at server start, so levels
    are built with it.
- **1.21.11 only.**
  - Hazard-block detection asked for 1.21.1's four-argument `entityInside`,
    found nothing, and treated every block as hazardous. It now uses the
    six-argument signature, and says so loudly if a signature change ever
    breaks the lookup again.
  - A mob whose effects changed is synced on the level thread: its
    `sendChanges` posts `EffectParticleModificationEvent`.

## Second review

Eight more reviewers went over the result: the hook check, the feature
annotations, the pool and tick context, the cluster tier, cross-dimension
parking, chunk-load refusal and explosions, 1.21.11 parity, and other mods'
code. Each finding below was checked against the vanilla, NeoForge or other
mod's code before it was changed.

Other mods:

- **Standalone Lithium stopped dimension threading.** Lithium checks that its
  chunk-status callbacks run on `Level.thread`, and a dimension ticking on a
  worker promotes its own chunks. The fix already existed but was applied only
  alongside SuperChunk; it is now applied whenever Lithium's class is there.
  The first chunk a player loaded in a worker-ticked Nether threw.
- **Lithium's `ai.poi` walked around the POI lock.** It overwrites
  `findClosest`, `find`, `getRandom`, `getCountInRange` and `getInRange` with
  walks over the section map that never pass a locked method, and on 1.21.11
  sends every villager's bed and job-site claim through two methods of its
  own. All of them now take the lock; the stream-returning one is collected
  inside it.
- **Lithium's block-entity lookup** (`util.block_entity_retrieval`) answered
  "none" on a worker-ticked dimension's thread, so hoppers there kept stale
  inventory caches with Lithium's default config. The level's owning thread
  now counts, as it does for vanilla's `getBlockEntity`.
- **Lithium 0.21's `random_block_ticking`** (1.21.11) steps the level's
  random-position generator itself, outside the method in which Skein gives
  each thread its own copy, so parallel random ticks lost each other's steps
  and picked the same blocks. Its method now uses the thread's copy too.
- **Lithium's `game_events.dispatch`** keeps a per-level map of sections with
  game-event listeners. A block entity's listener now joins and leaves the
  chunk at the barrier during a pass, like the events themselves.

Parallel ticks:

- **A refused chunk load deleted the asker** under NeoForge's
  `removeErroringEntities`/`removeErroringBlockEntities`, quarantined the
  level, or stopped the server under `crashPolicy = RETHROW`. Refusals are now
  their own exception type, kept out of vanilla's crash handlers and let
  through by every catch site: the asker's tick ends, and nothing else.
- **What still reached unloaded chunks from a worker:**
  - a fed dolphin looking for treasure, which now ticks on the level thread
    while it has the fish;
  - a villager past level 1 with no trades yet;
  - an allay whose liked note block was left in an unloaded chunk: it reads
    the block before it checks whether it still likes it, so every one of its
    ticks would have been refused before it could forget the block;
  - an unopened loot chest read by a hopper or broken by an explosion: a
    shipwreck's or ocean ruin's explorer map searched fifty chunks away, was
    refused, and the chest was left empty for good. The roll is now parked for
    the barrier, and a chest broken meanwhile drops what the roll produced;
  - large explosions with `loadedMarginChunks = 0`: the explosive pin now falls
    back to vanilla's own two loaded rings instead of switching off.
- **Items and orbs standing on blocks.** `stepOn` and `fallOn` act on the
  world too -- redstone ore lights under a dropped item, and a sculk sensor
  takes the step as a vibration -- and the cluster tier keeps its entities
  apart by nothing at all. Blocks that override either are found the same way
  as `entityInside` hazards and parked the same way.
- **Cluster tier isolation** now allows for how wide a target is: the chunk
  test pads the path by half the widest entity type, and the radius form pads
  by the widest outsider's box. Entities spawned during the tier join only
  after its last level, since isolation was decided before they existed.
- **1.21.11 `RandomSequences`** loaded from disk is built by a private
  constructor that the concurrent table was never installed on; both
  constructors are hooked now.
- **Ender pearls on 1.21.11** tick on the level thread. A pearl in flight
  renews a chunk ticket in its thrower's name, writing the thrower's own set of
  pearls, and a thrower in another dimension has its renewal wait for the
  barrier; a pearl whose landing is parked stops renewing.

Across dimensions:

- **Teleports with an override.** A teleport from a level the caller is not
  ticking was parked at the head of the base method, which handed the entity
  back to an override that then did its own part on the wrong thread -- an item
  merging with its neighbours, a pearl placing a ticket, a sleeping player
  being woken -- and did it again at the replay. The overriding vanilla
  classes and `teleportTo` now park at their own head.
- **`/save-all` from a command block** saved every level while the others were
  ticking. A save asked for from inside a tick waits for the barrier; parallel
  saving never runs from inside one.
- **Writes to another level's entities** from a pass -- a section move or a
  removal -- wait for the dimension barrier rather than the pass barrier,
  which is drained with that level still running; so does a game event sent
  into another level.

Smaller:

- Quarantine now also takes a level's entity tracking, explosion rays and
  exposure, and (1.21.1) chunk scan off the pool.
- The pool no longer drops its queue on shutdown: a coordinator still inside
  `invokeAll` would have waited for runners nobody would start.
- The server thread's state is dropped when the server stops.
- Annotations: the POI section flush also guards parallel saving; the pearl
  landing also guards dimension threading; SpeeDeeEntity's shared collider
  index is only an optimisation; 1.21.11's `entityInside` park is in-level
  only. The hazard suggestion is suppressed when writes are not being counted.

## Left as limits

- The governor books a phase's serial fallback under its parallel estimate
  (performance only).
- Other mods' code inside clustered ticks (`EntityTickEvent`, item-entity
  hooks) and modded explosion resistance run on workers.
- A mod that writes into another dimension through a chunk reference it
  already holds is not visible to Skein.
- The first chunk request a level makes into another dimension while both
  tick -- a command block's or `/schedule` function's `execute in` -- fails
  once; that level then ticks after the others. Scheduled functions run in
  the overworld's tick, so a data pack whose functions reach into other
  dimensions keeps the overworld serial.
- Portal travel waits for the dimension barrier, so an entity finishes the
  tick in the dimension it came from. The End-portal sand duplicator depends
  on the order within that tick and behaves differently.
- A modded entity that overrides `teleport` (`changeDimension` on 1.21.1) to
  act around the move still gets the entity back from the parked base method.
- Loot containers carried by entities (chest minecarts and boats) still roll
  their loot where they tick; vanilla's tables for them hold no maps.
- Mods whose block entities join shared networks (Create, AE2, Mekanism) are
  not detected; their block entities belong on `blockEntities.blacklist`.

## Verification

Core tests pass on both lines. Each integration leg starts a dedicated server
with the compat-test mod and runs every in-game suite: chunk cache,
explosions (32 seeded vanilla comparisons), explosion rays (144 explosions
against the serial loop), projectiles, cluster isolation, lookups, the
governor, chunk scan (1.21.1), tracking (7040 packets matched against the
level thread's), and SpeeDeeEntity where installed.

| Leg | Setup | Hook check | Result |
|---|---|---|---|
| plain121 | 1.21.1, SpeeDeeEntity | 172 checked, 0 missing | pass |
| lithium121 | 1.21.1, Lithium 0.15.4 with the nine options off, SpeeDeeEntity | 174 checked, 0 missing, 1 optional (the block-entity lookup, its Lithium option off) | pass |
| conflict121 | 1.21.1, four deliberate conflicts | 169 checked, 1 missing, 1 left to another mod; parallel autosave and entity tracking switched off, as intended | pass; the other mod's block-entity wrapper stayed outside Skein's for 80 parallel ticks |
| plain1211 | 1.21.11, SpeeDeeEntity | 171 checked, 0 missing | pass |
| absent1211 | 1.21.11, no optional mods | 168 checked, 0 missing | pass |
| pvp1211 | 1.21.11, Lithium 0.21.4 and pvp_bot 0.0.15 with the PvP server's config | 177 checked, 0 missing, 3 optional (the two `popResource` reads pvp_bot's overwrite leaves nothing to protect in; the block-entity lookup, its Lithium option off), 1 left to Lithium's raycast | pass |

Before this round's second review the check counted 139 hooks on the PvP
setup; the difference is the handlers it had been skipping and the hooks
added since. The first pvp1211 run of the second review failed one
assertion: the isolation test's oracle for the radius form still measured
from outsiders' positions only, which is the behaviour the review had
flagged. The oracle now pads by the widest outsider box, as the code does.

A refused optional chunk read in plain1211 (chunk -1,-1, when the tracking
test's fake player joins beside an unloaded chunk) also appears in the runs
from before this round; it returns vanilla's answer for an unloaded chunk.
