# Skein

By **TheProKnightGamer**.

Ticks Minecraft's dimensions on separate threads, and the work inside a
dimension -- random ticks, entities, block entities -- across separate spatial
regions. NeoForge 1.21.1, server-side. All four phases are on by default.

The server tick still ends when it always ended. Skein widens the middle of it,
then closes a barrier before anything downstream can observe the difference.

---

## What it actually does

### Dimension threading

Vanilla `MinecraftServer.tickChildren` walks its dimensions one at a time:

```
commandFunctions → overworld → nether → end → connections → players → send chunks
```

Skein turns the middle into:

```
commandFunctions → ┬ overworld ┐
                   ├ nether    ┼ barrier → connections → players → send chunks
                   └ end       ┘
```

Every level advances exactly one tick, and none of them can run ahead of
another. Dimensions share very little mutable state, which is what makes this
half nearly free.

The interception point is deliberately small. Rather than reimplement
`tickChildren`, Skein redirects the single call that feeds its loop and hands
back an empty array — so command functions still run before, connections and
players still run after, and every other statement in that method keeps its
exact position. If Skein declines the work (one dimension, profiling active,
disabled in config) the real array comes back and vanilla runs untouched.

`LevelTickEvent.Pre` and `.Post` still fire on the server thread — all the
`Pre`s before the phase opens, all the `Post`s after it closes. No mod's tick
listener gets handed a thread it has no reason to expect.

### The split

Everything below dimensions is the same idea applied to a different pile of
work: cut the level into square chunk cells and run the cells at the same time.

It rests on one geometric argument. A tick reads and writes the world around
itself -- collision, block edits, area queries for targets and paths, a sapling
turning into a tree, a hopper looking at its neighbours -- and every one of those
has a finite reach. If a piece of work sits at least `borderMarginBlocks` inside
its own cell, nothing it does that tick can touch a block or an entity section
belonging to another cell.

That argument is old. What is new is what happens to the work it does not cover.
**Four grids, not one.** With a 128-block cell and a 32-block margin the safe
interior of a cell is 64x64 out of 128x128 -- so a single grid leaves *three
quarters* of an evenly spread population sitting too close to an edge, and Skein
used to hand all of it back to the server thread.

So there are four grids, offset from each other by half a cell in x, in z, and
in both. Every position is interior to exactly one of them. Each piece of work is
assigned to the grid it is safely inside, and the four resulting groups run as
four consecutive passes with a barrier between them:

```
pass 0 ─┬ cell ┐        pass 1 ─┬ cell ┐        pass 2 …   pass 3 …
        ├ cell ┼ flush          ├ cell ┼ flush
        └ cell ┘                └ cell ┘
```

Nothing is left over. In the measurements below, out of ~1100 ticking entities
only the nine players were serial, and out of 1152 randomly-ticked chunks and
2674 block entities, none were.

The safety argument is unchanged and is still the only one: two things running at
the same time are in different cells of the *same* grid, each at least `margin`
inside its own cell, so they are at least `2 x margin` apart and neither can
reach what the other is writing. Work in different passes is separated by a
barrier rather than by distance.

Not everything being split is a point. An entity is; a chunk being random-ticked
is a 16-block span, and has to be interior along its whole width or a block
edited at its border reaches into the next cell. So the test is over a span, and
a point is just a span of zero width.

For every position to be interior to some grid, a cell has to be at least four
margins across -- and a chunk-wide span needs a little more than that, because
the width itself has to fit in the stretch where the two offsets hand over. That
is why the two defaults (8 chunks against 32 blocks) sit where they do, and the
config raises the cell size to meet the margin rather than quietly pushing work
back into the serial set. Raising the margin costs cell size -- and therefore
the number of cells -- rather than costing coverage.

### Scheduled ticks (off by default)

Redstone, fluid flow, falling blocks, crops on a timer. This is the one phase
where order is the whole point, so it does not use the grid: a scheduled tick
carries a sub-tick number precisely so that its position in the tick is
defined, and a grid can drop two blocks that are five apart into different
passes.

So the split here is by **connected component**. Work within a link distance of
other work is one group, run in vanilla's own order on one thread; groups
further apart than anything can reach run at the same time. That distance is
measured horizontally only: a chunk is a column, and two ticks in one write to
the same chunk's heightmaps and block-entity maps however far apart they are
vertically, so a column is never split.

How components are found matters as much as the idea. Bucketing the world at the
link distance and joining adjacent buckets is the obvious implementation and it
is far too eager: two points in adjacent buckets can be nearly two bucket widths
apart, and adjacency is transitive, so a row of separate contraptions chains into
one. Measured, thirty-six redstone sites ninety-six blocks apart -- with a link
distance of eighty -- came out as **four** components. Each bucket now carries the
bounding box of what is actually in it and two buckets are joined only when those
boxes come within the link distance, which turns the same thirty-six sites into
thirty-six components and the overlap from 3.4x into 5.2x. The join is done once
every tick is filed, so a box that grows later cannot be missed. That gives an exact
guarantee -- no pair of ticks that could affect each other is ever reordered --
and costs the property the grid has: parallelism now depends on how spread out
the world is rather than on how much work there is. One spreading lake is one
component and runs exactly as vanilla runs it. Forty separate farms are forty
components.

Ticks whose reach nothing bounds -- command blocks, which run arbitrary
commands -- cannot honestly be placed in any component, so one of them anywhere
in a tick's collected set sends the whole set back through vanilla's serial
loop. A structure block reaches just as far but never appears in that set: it
has no tick of its own and places from a neighbour update, so its placement is
parked for the barrier instead, which covers every phase rather than this one.

It is off by default. It reproduces vanilla's fluid interactions block for block
in testing (below), but it has been reported to hang a world that contains
0-tick redstone, and that report is not yet explained.

### Random ticks

`ServerLevel.tickChunk` is where weather, ice and snow, and every randomly-ticking
block and fluid in a chunk get their turn. On a server with a high
`randomTickSpeed`, a lot of loaded chunks or a lot of growing things it is the
largest single cost in the tick, and it is the best fit for the split there is: a
random tick reads and writes a handful of blocks around itself and nothing else.
Grass spreads one block, fire five, a sapling turns into a tree that can be eight
across -- all comfortably inside the default margin.

Vanilla walks its ticking chunks once and does three things per chunk: increment
inhabited time, offer the chunk to the mob spawner, random-tick it. Only the last
is safe to run across threads, so Skein files the chunk instead of ticking it,
lets the loop finish everything else serially, and runs the phase just before the
loop broadcasts its results. Which means all spawning now happens before all
random ticking rather than the two being interleaved chunk by chunk; the only
thing that can see the difference is a spawn attempt that would have noticed a
block grown earlier in the same loop, one tick sooner.

### Entities

Vanilla's per-entity consumer is reused verbatim -- despawn checks, tick
freezing, the entity-ticking-range test, passenger handling -- so Skein only
decides which thread each call happens on. Riding stacks stay on one thread
because a passenger is ticked by its vehicle, and players are pinned by default:
a player tick reaches well past the player, into advancements, stats, the open
container and the game-mode state machine, and none of that is bounded by the
margin.

### The cluster tier

A cell is a *conservative* conflict test. It assumes any two entities inside it
may reach each other, so the whole cell runs on one thread. That is what makes
the geometry cheap, and it is also its ceiling: a cell is keyed on `(x, z)`, so
every entity standing on one column shares a key at every cell size, forever.
Shrinking cells separates different columns and can never separate one. Five
thousand snowballs on a farm smaller than a cell get exactly one thread, and no
amount of tuning changes that.

The test the geometry stands in for is simply *can these two entities reach each
other*, and applied directly it is far more permissive. In particular a thrown
projectile can only hit what `Entity.canBeHitByProjectile` admits, which is
`isPickable`, which is false for every projectile -- so **two snowballs cannot
touch each other at any distance**, and may tick at the same instant however
crowded they are.

So for an audited set of types, Skein orders by precedence rather than by
position. Walking vanilla's own list order, each entity is given

    level[i] = 1 + max{ level[j] : j < i and i conflicts with j }

which gives two properties, and they are the whole correctness argument:

- Two entities on the **same** level never conflict -- if they did, the later
  one would have been pushed past the earlier one -- so they may run at the same
  time on different threads.
- Two entities that **do** conflict always land on different levels, in list
  order, and levels run one after another, so they execute in the order vanilla
  would have run them.

Every pair therefore either behaves exactly as it does serially, or is a pair
that provably cannot observe the other. Levels are the barriers; there are no
cells and no four passes.

Two things bound it, and both are real:

**The game event.** `GameEvent.register` gives every event a notification radius
of sixteen blocks and `PROJECTILE_LAND` fires on every projectile that dies,
writing into every vibration listener in that cube. At reach 16 every entity in
a farm conflicts with every other and the graph is a solid lump -- measured at
828 levels for 9,236 entities, which is the serial order with a barrier between
every handful. So the dispatch is parked until the barrier, like the navigation
sweep: same tick, same listeners, deterministic order, one pass of latency.

**Reaching outside the tier.** A snowball cannot hit another snowball, but it
can certainly hit a *mob* -- and two snowballs that provably cannot touch each
other can still be inside `LivingEntity.hurt` on the same mob at the same
instant, which trips that mob's own `ThreadingDetector`. Widening every
projectile's reach would re-tangle the graph that makes the tier worth having,
so instead the tier declines the entities it cannot vouch for: anything within
`isolationBlocks` of a gridded entity is handed back to the grid, where the
margin keeps the pair on one thread. A farm with nothing else in it excludes
nobody; a farm with mobs standing in it gives up the projectiles near them and
keeps the rest.

For vanilla snowballs and eggs a radius is far too blunt, and the case it
fails is the ordinary one: a player watching a bubble column from beside it is
within any sane radius of every snowball in the column, the whole column goes
back to the grid, and a column is one cell -- one thread. Measured on a client
with a player 1.8 blocks from an 1,880-snowball column, the cluster tier never
ran at all. So those two types are tested against what they can actually hit
instead. The only way one writes to an entity outside the tier is by hitting
it, and vanilla hits only an entity whose hitbox, grown by 0.3, crosses the
segment the projectile travels this tick. Skein pads that segment by the 0.3
plus a quarter block (fluid pushing, the only thing that can bend it before the
hit test, moves it a hundredth of a block) and demotes the projectile only if
some gridded entity's hitbox -- a multipart boss's parts included -- touches
the result. That is both tighter than the radius and safer: a fast projectile
aimed at a mob nine blocks away is outside an eight-block radius and still
demoted. Modded subclasses keep the radius, and
`entities.cluster.exactProjectileIsolation = false` restores it for everything.

**What the block does to them.** Admission is by entity type, but what an
entity's tick reaches is also decided by whatever block it happens to be
standing in, and that reach belongs to the block. A wooden pressure plate's
`entityInside` has sensitivity EVERYTHING -- a dropped item sets a block,
updates its neighbours and schedules a tick -- and a target block's
`onProjectileHit` awards a statistic to a shooter who may be in another chunk
entirely. With no separation between two clustered entities, both of those are
unsynchronised writes: two items on plates four blocks apart meet inside one
`PalettedContainer`, which throws *Accessing PalettedContainer from multiple
threads*, or in two sections of one chunk meet in the shared heightmap, where
nothing checks at all. So the blocks that write on an entity's behalf are
resolved once at startup -- by asking which class declares the override, which
covers modded blocks without a list -- and for those, and only those, the call
is parked to the barrier. A bubble column writes nothing (it only pushes the
entity) and so still acts in-tick, which is why the farm this tier exists for is
unaffected.

A pair conflicts at whichever of the two reaches further, so a pair where *both*
reach zero cannot conflict at any distance. The index is therefore kept as two
chains through the same buckets -- one holding everything, one holding only the
entities whose reach is above zero -- and an entity that reaches nothing walks
only the second. Without that, a single item entity (reach 2) among five thousand
projectiles raises the pile's maximum reach above zero and puts every one of the
projectiles through the full neighbourhood scan, for edges that cannot exist.

Which blocks that catches is worth stating, because the obvious test is wrong in
an expensive way. Asking which class overrides `entityInside` finds every block
that *reacts* to an entity, not the ones that *write* because of it -- and a
bubble column overrides it to push the entity and spawn two particles. Parking
that put the hottest operation in a projectile farm on the coordinator: profiling
a farm with eight players watching found `DeferredBuffer.drain` at 20.8% of all
server-thread time and `BubbleColumnBlock.entityInside` at 20.6%. Listing the
block in `inertBlocks` took those to 2.0% and 5.7%, and 18% off server-thread
time overall.

The tempting generalisation -- watch the replays and release any block that never
writes -- is unsound, and instructively so. `BasePressurePlateBlock` only calls
`setBlock` when the signal *changes*, so a plate held down looks inert for as
long as anything stands on it, would be released on that evidence, and would race
the instant it depressed. Absence of an observed write is not absence of a write.
So `inertBlocks` is an audited list and Skein only *suggests* candidates it has
never seen write, in the log, without acting on them.

The tier also declines a pile whose graph levels too narrowly to be worth the
barriers -- items packed tighter than they merge, say -- in which case nothing
has run and every entity is filed into the grid exactly as before.

Admission is a list of audited types, not a structural test, because what
qualifies a type is that its whole tick writes no blocks and reaches nothing
outside the list, and neither can be asked of a modded entity at runtime.

### Knowing when not to bother

Splitting work is not free. Every phase files all of its work before it can
decide how to split it, and a split then costs a barrier, a `TickContext`
bracket per cell, and a replay of whatever the pass deferred. On a busy level
that disappears into the win. On a quiet one it is the entire cost.

Count thresholds cannot tell the difference, because the thing that matters is
time and they measure population. Two thousand hoppers clear any sane count and
still do not add up to enough work to pay for a barrier -- measured, that world
had the phases saving 0.65ms of work while the machinery around them cost more
than that, leaving the tick 0.31ms *slower* than with the mod switched off.

So Skein measures instead. Each phase is timed end to end -- filing, barriers,
deferral replay and all -- and the figure goes into one of two running averages
depending on which way it ran. The phase then takes whichever has been cheaper,
and periodically spends one tick on the other to keep that estimate honest. A
phase that is not paying for itself simply stops running in parallel, and the
vanilla path it falls back to costs nothing at all: no filing, no partition, no
deferral.

The probe interval scales with how decisive the gap is, which matters because a
probe costs one tick on the slower path. Close together, probing is cheap and
frequent; far apart it is expensive and there is nothing to learn, so it backs
off. That keeps the amortised cost of probing at roughly one per cent of the
faster path however far apart the two are.

`/skein status` shows the current verdict and both estimates, which is usually
the fastest way to understand why a phase is or is not threading:

```
adaptive (overworld): random ticks serial (0.79/0.42ms),
                      entities parallel (2.92/17.15ms),
                      block entities parallel (0.09/0.28ms)
```

### Running with Lithium

The two mods optimise opposite halves of the same problem, and having to choose
between them costs real time. Profiling a busy farm put `Level.getEntities` at
45% of all worker time and 18% of the coordinator's -- walking a
`LongAVLTreeSet` of sections and collecting through a stream buffer, once per
projectile per tick. Skein now also trims impossible targets from ordinary
snowball/egg queries inside its threaded passes. Lithium optimises broader
query and game-logic paths, including quiet levels where threading does not pay.

The known Lithium conflicts are separately switchable. Skein asks Lithium's
startup `LithiumConfig` for the effective option of a
representative mixin in each -- rather than parsing `lithium.properties`, which
would mean reimplementing its precedence rules, where disabling a parent package
disables its children. Reading the startup snapshot also prevents a file edit from
claiming that an already-applied mixin is disabled. If all nine options below
answer "disabled", the in-level phases run, and Skein's own `neoforge.mods.toml`
asks for exactly that: Lithium reads a `lithium:options` table from every mod
before it picks its mixins, and so does the copy SuperChunk bundles, so
installing the two together needs no config at all:

```
mixin.alloc.chunk_random=false
mixin.util.world_border_listener=false
mixin.world.block_entity_ticking.world_border=false
mixin.world.chunk_access=false
mixin.block.hopper=false
mixin.chunk.entity_class_groups=false
mixin.entity.inactive_navigations=false
mixin.entity.collisions.unpushable_cramming=false
mixin.util.block_entity_retrieval=false
```

Lithium lets its own `lithium.properties` overrule a mod, so a line there that
turns one back on still wins. Skein then prints exactly those lines, for exactly
the options still enabled, and keeps the in-level phases off; dimension threading
carries on. Everything else Lithium does keeps working. Anything unreadable is treated as still-enabled, because the
failure mode of guessing the other way is silent world corruption.

A few of Lithium's other features are made safe rather than switched off, and
Skein does that whenever it finds Lithium, whatever the config says:

- its chunk-status callbacks and its block-entity lookup accept the thread
  that is ticking the level, not only `Level.thread`, so dimension threading
  works beside Lithium's defaults (the lookup is why hoppers in a dimension
  ticked on a worker used to keep stale inventory caches);
- the point-of-interest searches `ai.poi` rewrites, and the two methods it
  adds for villagers claiming beds and job sites, take Skein's POI lock;
- Lithium 0.21's random block ticking picks positions with each thread's own
  copy of the level's little random-position generator, as vanilla's
  `getBlockRandomPos` does under Skein;
- a block entity's game-event listener joins the chunk at the barrier during
  a pass, so `world.game_events.dispatch`'s per-level map is only written by
  one thread.

With the updated **SuperChunk cell bridge**, SuperChunk also writes these options
off before Lithium applies its mixins, for either bundled or standalone Lithium.
SuperChunk finds Skein by package, so it needs a build that knows
`com.theproknightgamer.skein`: one that only knows the older package roots does
not see Skein at all, and its chunk-manager thread check then throws
`Async ticking server chunk manager` on Skein's dimension workers, which drops
the nether and the end back to serial on their first tick. SuperChunk builds
without the bridge keep the dimension-only compatibility policy. Entities, random blocks, block entities,
and opted-in scheduled ticks can then use Skein's workers. Chunk generation,
lighting, and serialization stay with SuperChunk; Skein's parallel-saving phase
is pinned off. Config reloads retain the compatibility policy.

With SuperChunk, set `player.sendAtChunkSending=true` in `superchunk.properties`
if your render distance is above your simulation distance. It is SuperChunk's
default now, but configs written by older builds carry `false`. With it off, the
ring of chunks between the two distances never becomes sendable. On a memory
connection (singleplayer) vanilla's chunk sender re-checks every pending chunk
every tick, so a 32/20 client rescanned about 2,600 unsendable chunks every tick
and never saw them -- 1,852 of 4,510 loaded chunks ever reached the player.

The [SuperChunk parallel verification](docs/superchunk-parallel-2026-09-22.md)
records the compatibility checks and paired benchmarks: 2,000 snowballs averaged
37.17 → 2.33 ms per tick, and the cinematic nuke's explosion tick averaged
5.73 → 3.25 seconds on the test machine.

Measured together, on the quiet world that used to be Skein's worst case:

| | mean ms/tick |
| --- | --- |
| neither | 2.17 |
| Skein, every phase forced parallel | 2.48 |
| Skein, adaptive | 1.99 |
| **Skein adaptive + Lithium** | **1.71** |

The two compose better than either alone, and the adaptive governor is why. On a
quiet level it puts the entity phase on the serial path -- and Lithium makes that
path 3.6x cheaper (a 47ms serial estimate becomes 13ms). On a busy one it puts
the same phase on sixteen threads. Neither mod has to lose.

On the *heavy* farm the two came out identical at 4.36 ms/tick, which is worth
stating plainly: Lithium cut entity work from 46.6ms to 32.1ms of CPU, but with
sixteen threads already absorbing that phase the tick was bounded elsewhere, so
none of it reached the clock. Lithium pays where the threads are not helping.

Correctness was checked rather than assumed: the water-and-lava fingerprint over
nine basins is identical to Lithium **alone** (one basin differs from
no-mods-at-all, reproducibly, with Skein disabled as well -- so it is Lithium's
own deviation and not an interaction), item totals under heavy merging match the
baseline exactly, and no run produced an exception, a stall or a tick failure.

### Running with carpet and WorldEdit

Carpet redirects every block read in `Explosion.explode` through
`noBlockCalcsWithNoBLockDamage`, which answers bedrock while its
`explosionNoBlockDamage` rule is on and the real block otherwise. That redirect
sits in the ray loop Skein casts on its pool, so Skein used to leave the loop to
carpet for good ("switches off: explosion rays on the pool"). It now shares it:
each explosion asks carpet's rule before casting, so the rays go to the pool with
the rule off and through carpet's redirect with it on. Skein's held-chunk block
read wraps the same call from outside carpet's redirect, and it too lets every
read through while the rule is on; before, it skipped the redirect, so explosions
broke blocks the rule should have protected.

WorldEdit adds a four-argument `LevelChunk.setBlockState` that sets a flag and
calls vanilla's. Skein names vanilla's by its full descriptor, so its block-change
hooks always land on the method every change passes through, whatever order the
mixins apply in.

### What other mods see of a worker

A worker ticking a level is that level's thread for the tick, and other mods
are told so in the two ways they ask. Forge's and NeoForge's `EffectiveSide`
reads the thread group, and the workers are started by the server thread, so
they share its group and answer `SERVER`. Cupboard reads the thread name: it
holds back any entity added from a thread without "server" in its name until
the next add on one that has it, which under Skein might never come, so drops
and spawns sat in its queue. The workers are named `Skein Server Worker #N`.

### Mods Skein makes room for

A pack is tested by running it with and without Skein, placing every modded block
and entity in a force-loaded area, and comparing the logs (`bench/packs/`). What
that and a reading of the mods' code turned up, and what Skein does about it:

- **Neruina** keeps one ticking-error handler for the whole server and ticks it
  from every level, so two dimensions ticking at once raced on its list of recent
  errors and the loser was dropped back to serial ticking. Its bookkeeping now holds
  a lock, and Skein's own guards sit inside Neruina's (see above).
- **Create.** Belts, chain conveyors, arms, fans, pumps, funnels and chutes reach
  less than the margin and tick in parallel as they are. What is shared beyond
  that -- a kinetic network's members and stress totals, the rotation walks that
  add and remove them, the brass tunnels' static scratch maps, redstone-link
  frequencies, the deployers' captured drops, and Create 6's logistics network and
  caches -- is entered through a handful of methods that now hold one lock. They
  run when something changes, not every tick. Display links, redstone links and the
  6.0 logistics blocks (factory panel, packager, repackager, packager link, stock
  ticker, redstone requester) reach further than any margin and tick on the level
  thread whenever they are installed. Contraptions are filed by their size; see
  below.
- **Ritchie's Projectile Library** (Create Big Cannons' shells and others) fills a
  per-level set of chunks to force-load from every projectile's tick. Two shells
  in different cells corrupted it and a worker spun forever; the set now holds a
  lock.
- **Large entities.** An entity is filed by whichever is larger, its follow range
  or its own box plus three blocks. A mob is a block or two wide and nothing
  changes; a Create contraption, a ship or another moving structure wider than a
  cell's margin keeps to one cell or ticks on the level thread, instead of two of
  them reaching the same blocks from different cells.
- **Shared vanilla and loader state** behind mods' ticks now holds a lock: saved data (a level's `HashMap` of named data, which mods reach from any tick, and the overworld's from any dimension); the player list's stats and advancements maps, which every fake player a machine builds looks itself up in; the loader's fake-player factory, a static `HashMap` machines in different dimensions or cells call at once; NeoForge's capability-cache listeners, registered by pipes and machines and invalidated by every block entity placed or removed.

### Packet batching

Not a phase, but it belongs here because Skein was quietly making this worse.
`MinecraftServer.tickChildren` calls `suspendFlushing()` on every connection
before the level loop and `resumeFlushing()` after it, so for the whole loop
vanilla writes packets without flushing and flushes each connection exactly once.
Whether a send takes that path is decided by
`!suspendFlushingOnServerThread || !this.server.isSameThread()` -- a
thread-identity test, precisely the kind of gate this mod exists to replace, and
a worker fails it. So every packet sent from a worker took `writeAndFlush`
instead of `write`: a netty flush and a write syscall *per packet* rather than
per connection per tick. `playSeededSound` and `broadcastEntityEvent` are called
straight out of ordinary mob ticks, so every mob that made a sound or took damage
was forcing one.

Answering "yes, this is the ticking thread" for a worker inside a level tick is
not a deferral: the packet reaches the connection at the same moment in the same
order, and `resumeFlushing()` flushes unconditionally at the end of the same
tick. Only the syscall moves. It does not show up in ms/tick -- the cost was on
netty's threads -- which is exactly why it went unnoticed.

### The periodic autosave (off by default)

Every autosave interval vanilla walks every loaded chunk and serialises the dirty
ones -- `ChunkSerializer.write` runs 24 sections through the block-state codec,
plus biomes, light layers and every block entity's NBT -- with no time budget of
any kind. It is the largest single stall a vanilla server has, and unlike every
other phase here it is a *latency* problem rather than a throughput one: it
amortises to almost nothing per tick and then drops a fistful of ticks at once.

It is also the easiest thing in the game to split, for an unusual reason. The
concurrency argument is not a reach bound but simply that **nothing is ticking**:
`saveEverything` runs after `tickChildren` has returned, so every phase is closed
and the server thread is alone in the world. Reads of chunk state cannot race a
writer, and chunks are independent of one another. Two plain fastutil maps that
`save` reads and writes -- `chunkSaveCooldowns` and `chunkTypeCache` -- are
serialised; everything else was already per-chunk or thread-safe (`IOWorker`
submits through a mailbox).

Only the periodic path is touched. The `flush` path -- startup, shutdown,
`/save-all flush` -- is a `managedBlock` handshake with the main-thread executor
and stays exactly where it is.

Off by default for one reason: `ChunkDataEvent.Save` fires on whichever worker
serialised the chunk, so any mod listening to it has to tolerate that. Nothing in
Skein can make that safe from the outside, so it wants your own mod list and a
soak before you turn it on.

### Block entities

Usually the largest cost on a server that has been played on for a while:
hoppers checking their neighbours, furnaces burning, chests looking for players,
spawners scanning. All of it is at a fixed position and almost all of it reaches
a block or two, which makes it a better fit for the split than entities are.

Almost all. A few vanilla block entities reach much further than any sane margin
-- a beacon looks for players within fifty blocks and adds effects to them, a
conduit further still, a bell makes raiders glow at forty-eight, an end gateway
teleports things into another dimension outright. Those tick on the level-owning
thread, and they are the default contents of `blockEntities.blacklist`.

This is the phase most exposed to other mods. An entity that reaches across the
world is unusual; a machine that does is a genre. If a tech modpack misbehaves,
this is the first switch to try.

## The safety model

Three mechanisms.

### 1. Ownership instead of identity

Vanilla gates a lot of code on `Thread.currentThread() != someThreadCapturedAtWorldLoad`.
There are exactly two such gates in the level and server code, and both matter:

| Where | Vanilla behaviour off-thread | Skein |
| --- | --- | --- |
| `Level.getBlockEntity` | returns `null` | accepts the thread currently ticking the level |
| `ServerChunkCache.{getChunk, getChunkNow, getChunkFuture}` | reroutes or returns `null` | accepts the current owner |

The first one is not optional. Left alone, it silently reports every chest,
hopper and furnace in the game as absent — far worse than the race it guards.

### 2. Deferral instead of locking

During a parallel pass the entity registry is **read-only**. Every structural
write is queued and applied by the coordinator, in cell order, the instant the
pass ends:

- entity spawn (`ServerLevel.addEntity`)
- entity section transfer (`onMove`) and teardown (`onRemove`)
- scheduled block and fluid ticks (`LevelTicks.schedule`)
- block events (`ServerLevel.blockEvent`)
- block-entity ticker registration
- the navigation sweep in `ServerLevel.sendBlockUpdated` (below)
- structure block placement, whose reach is whatever the template file says
- the `UNKNOWN` chunk ticket `getChunk` adds on a miss (below)
- a portal transition (`Entity.handlePortal`), which waits for the *dimension*
  barrier rather than a cell's: everything it writes belongs to the level being
  arrived in, and a cell's buffer is drained while that level is still being
  ticked by somebody else

The alternative — locking `EntityLookup`, `EntitySectionStorage` and friends —
means holding locks across code that calls back into arbitrary mod logic.
Deferral costs a sub-tick delay instead: a mob spawned mid-pass does not answer
area queries until the pass ends, and the spatial index can be one section
stale. Neither is observable in gameplay; sections are an index, not the truth,
and area queries still filter on each entity's real bounding box.

Removal is the one deferral that needs care. Vanilla's `onRemove` ends by
setting the entity's level callback to `NULL`, which is what makes every later
`onMove` on a dead entity a no-op. Park the removal and that guarantee goes with
it — a mob picking up an item removes it from one cell while the item is moving
in another, and applying the removal first would let the move put the discarded
item *back* into a section. So Skein detaches the callback immediately, in the
same order vanilla does, and only the teardown waits; parked moves are dropped
once their entity is detached.

### 3. Per-thread copies of things vanilla keeps per level

Seven pieces of state are single-threaded by construction in vanilla, so each
thread gets its own for the duration of a pass:

- **`CollectingNeighborUpdater`** is a state machine with a shared stack and a
  recursion counter. The chain-length cap becomes per-thread rather than
  per-level.
- **`Level.random`** is wrapped in a delegating source. Vanilla's
  `LegacyRandomSource` keeps its seed in an `AtomicLong`, so integer and float
  draws survive concurrency; its gaussian caches the second value of each pair
  in two plain fields, and two threads drawing at once hand each other the same
  number. Outside a pass every draw still comes from the level's own object, on
  vanilla's own stream.
- **NeoForge's block-snapshot capture** (`Level.captureBlockSnapshots`). This is
  the nastiest of the five: while the flag is set, `Level.setBlock` skips
  `markAndNotifyBlock` entirely, so one thread opening a capture bracket would
  stop *every other thread's* block changes in that dimension from reaching
  clients or triggering physics.
- **NeoForge's block-drop capture** (`Block.capturedDrops`) — a plain `static`,
  and therefore a bracket shared by every thread and every dimension at once.
  Two cells breaking a block in the same tick would have one bracket overwrite
  the other's list and hand one break's items to the other break's event.
- **`Level.randValue`**, the two-line linear generator behind
  `getBlockRandomPos`. It is a plain `int` stepped in place, and every random
  tick and every ice-and-snow check goes through it, so two threads picking
  positions at once lose each other's updates and start landing on the same
  block.
- **`ServerChunkCache`'s chunk cache**, which is three unsynchronised arrays.
- **`RedStoneWireBlock.shouldSignal`**, the flag a wire clears while it works
  out its own power so that it does not count its neighbour as a source. This
  one is not per level at all -- it is a field on the block singleton, so it is
  one boolean for every wire in the game, and the bracket spans six calls into
  arbitrary block code. While any thread holds it false every
  `hasNeighborSignal` in the world reads a live wire as zero, which lights
  torches and opens doors that nothing later puts back. It is therefore the one
  of these that is per thread all the time rather than only while a pass is
  open: two dimensions on their own threads collide on it with every in-level
  phase turned off.

<p>And five pieces of vanilla state are shared rather than copied, and so are
put behind a lock instead:

- **`RedstoneTorchBlock.RECENT_TOGGLES`**, the sixty-tick burnout history, is a
  `static WeakHashMap` of plain `ArrayList`s written from torch ticks and torch
  neighbour updates. Two threads flipping torches at once is the classic
  `HashMap` failure: a concurrent resize corrupts a bucket chain and the next
  lookup never returns. No exception, no visible corruption -- one thread
  spinning forever, a barrier that never closes, and a server that stops without
  saying anything. Redstone that flips constantly hits it fastest, which is what
  a 0-tick contraption is. Both entry points now take one lock; it is a leaf,
  with nothing under it that touches the world or takes another lock.
- **`Level.subTickCount`**, the counter every scheduled tick is stamped with, is
  a plain `long` incremented on every schedule. It is the whole ordering
  guarantee the tick system offers, and two threads scheduling at once lose an
  increment and produce two ticks claiming the same place in the sequence. Now
  an atomic.
- **The point-of-interest index** (`PoiManager`, and the `SectionStorage` it
  extends). Villagers, iron golems, raiders and bees all reach it straight out
  of their ticks -- a bed, a job site, how many sections away the nearest
  village is -- and a lightning strike reaches it from a random tick. Under it
  are a plain section map, a plain dirty set, a village distance graph and, on
  each record, a bare `freeTickets--`. Distance is no help: two villagers three
  hundred blocks apart are in different cells and the same hash table. One lock
  per level covers both halves; the queries that hand back a lazy stream are
  collected inside it, since otherwise the section is read after the lock is
  gone.
- **A raid's membership** (`Raid`). The wave sets, the leader map and the
  hero set are plain collections, and it is the raiders that write them rather
  than the raid: one that wandered out rejoins from its own `aiStep`, a patrol
  leader recruits the ones around it from a goal, a dying one removes itself
  and credits its killer, and every hit any of them takes walks the whole
  membership to recompute the boss bar. A raid covers a village of radius
  ninety-six, so two of its members are routinely in different cells of one
  pass, and the shared object is the raid rather than a block or a section, so
  the margin buys nothing. A lost insertion is worse than it sounds: the join
  reports success either way, leaving a raider in a raid that will never count
  it, never eject it and never clear it when it dies. One lock per raid, over
  the entry points a raider's tick has. `Raids.tick` needs none -- it runs
  earlier in `ServerLevel.tick` than the entity loop, on the same thread.
- **`RandomSequences`**, which every loot roll in the game draws from. Vanilla
  stamps a `random_sequence` on every table it ships, so a decaying leaf, a
  zombie burning at sunrise and a harvested crop all end up on one
  `Xoroshiro128PlusPlus` per table id -- and on one table of them for the whole
  server, since the overworld's instance is handed to every other dimension as
  it is built. Looking a sequence up inserts it, which is a structural write to
  a plain hash map from a place nothing here would think to look, so the map is
  made concurrent; the draws are locked rather than made per thread, because a
  random sequence exists precisely so that loot is reproducible from the save
  and a private stream would quietly throw that away.

And one piece is shared but made race-free rather than copied: **`PathTypeCache`**,
the 4096-entry table every navigating mob hits hundreds of times per path. Two
threads ticking cells far apart never ask about the same block, but 4096 slots
across a whole world means they collide in the *table* constantly, and vanilla
writes the position and the path type as two separate array stores. An
interleaving that leaves one thread's position next to the other thread's type
is undetectable to the reader — it looks up its own block, finds a matching
position, and is told that lava is walkable. Skein stores the pair as one
immutable object per slot, so there is nothing to interleave.

### The navigation sweep

`ServerLevel.sendBlockUpdated` ends by walking `navigatingMobs` — every mob in
the level that is currently pathing — and recomputing the path of any whose
route crosses the changed block. A path is not bounded by the margin, so a mob
placing a block in one cell can reach into a mob being ticked in another, mutate
its navigation mid-tick, and iterate a set that a third thread's spawn is
writing to. This was the stated reason entity threading used to be off by
default.

Rather than reimplement the method, Skein replays it. The call is parked, and at
the barrier `deferring()` is false, so what lands there runs the entire vanilla
body — client updates, path-type invalidation, navigation sweep, and any other
mod's injections into it — on the coordinator, with nothing else in the level.
The path-type cache is invalidated eagerly as well, because it is thread-safe
and a mob later in the same pass has to see the block that just changed.

What that costs is one pass of latency on path invalidation: a mob that ticks
later in the *same* pass keeps its stale route for one tick. Mobs in later
passes see the recomputed one.

### When a mod's type fails in parallel

Some mods keep state that is not safe to share: a static map, a network registry,
a cache built on first use. Code like that usually works on one thread and throws
the first time two threads reach it at once. If an entity, a block entity, or a
block or fluid's random tick throws inside a parallel pass, Skein logs it once and
sends that whole type back to the level thread. A demoted entity or block entity
type ticks with the serial set; a demoted block or fluid has its random ticks held
until the end of each pass, where the block is read again first, since something
else in the pass may have replaced it. From then on the type runs exactly where
vanilla runs it, through vanilla's own error handling, so a bug that has nothing to
do with threading still shows up exactly as it would without Skein.

Skein catches the failure nearest the tick, inside any other mod's wrapper of the
same call. Neruina wraps every entity, block entity and random tick to suspend
whatever throws; it now sees only failures on the level thread, where a type that
merely failed in parallel no longer fails, and never sees a refusal (Skein turning
down a chunk load from a worker), which it used to treat as a crash.

A race can also corrupt a map so that the next thread into it spins forever, which
no amount of catching helps. When a parallel phase has been stuck for ten seconds,
Skein's report names what each stuck worker was ticking and demotes that type. The
stuck thread cannot be stopped, so the server's watchdog ends the run.

Every demotion is written to `config/skein-demoted.txt` and read back at the next
start, so a type that threw or hung once is not given the chance again.
`/skein status` lists what has been demoted, and `/skein unquarantine` gives all of
it another chance and empties the file. Two limits: a race that corrupts state
without throwing or hanging is not caught, and the failure is charged to the type
that was ticking, which is usually but not always the type at fault. With
`crashPolicy = RETHROW` nothing is demoted: the first failure crashes the server
with a report.

---

## Where the speed comes from

Beyond the four-grid split, and beyond simply having more of the tick inside it,
three things were in the way. All three are shared by every phase, which is why
the split, the barrier and the deferral machinery are one implementation used
four times rather than four implementations.

**The chunk lock.** `ServerChunkCache.getChunk` adds a distance-manager ticket
on every miss of its four-entry cache, and entities call it several times each
per tick simply to ask what fluid they are standing in. Skein used to take a
per-level lock on every one of those misses, which serialised a large part of
the pass. It is now split in three: a per-thread cache large enough to hold a
whole cell's working set rather than four entries; a lock-free hit against the
published chunk map for a chunk that is already loaded past the requested status
— there is nothing left to load, so the only remaining write is the ticket, and
that is parked for the barrier; and the real vanilla call under the lock only
for chunks that genuinely have to be brought in.

Parking the ticket is not a shortcut. `UNKNOWN` tickets live one tick and are
purged at the top of the chunk-source tick, which has already happened by the
time a pass opens — so a ticket added at the barrier and a ticket added inline
are the same ticket, with the same lifetime.

**The deferral queue.** One `ConcurrentLinkedQueue` per level, shared by every
worker, cost a CAS and a node allocation per deferred write — thousands per tick,
since every entity that crosses a section boundary produces one — and made the
replay order depend on how the threads happened to interleave. Each cell task
now owns a buffer nothing else touches, and the coordinator drains them in cell
order. Section moves and removals are stored as an opcode and a reference rather
than as a lambda, which takes the commonest deferred write in the game down to
zero allocations.

**The barrier.** The pool waits by spinning briefly before parking, in both
directions, and wakes only workers that are actually parked. A pass is a few
milliseconds of work split into pieces that finish microseconds apart; a barrier
that parks immediately pays a thread wakeup for every one of them.

Work is dispatched by index, with at most one queued runner per worker plus
the coordinator. Runners claim the next index from the same batch, so cells and
cluster slices stay available to every helper without allocating a lambda and
queue node for each task. Nested dimension/region batches still share the pool,
and a batch waits for every runner before returning or propagating a failure.

The conflict graph stops scanning when an entity has reached the highest
possible precedence, and declines immediately when the graph exceeds the useful
number of levels. Scheduled-tick grouping resolves components once per bucket,
uses four neighbour lookups rather than eight, and appends items using stored
node indices. Isolation queries outside the outsiders' bounding box skip the
spatial table entirely. Measurements and verification details are in
[the scheduling optimization report](docs/optimization-2026-09-21.md).

A further pass indexes positive-reach entities into the buckets they can touch,
so a mixed population's zero-reach entities need only one incoming lookup.
Cached precedence bounds stop scans through older bucket entries once they
cannot change the result. Wider buckets reduce ordinary range queries to eight
probes in the usual case; tiny outsider sets use linear scans. Grid placement
computes the offset and cell together, and cells sort in place without temporary
arrays. See [the second optimization report](docs/optimization-2026-09-21-round2.md)
for comparisons against the first optimized build.

Explosion rays reuse the fluid state from the block they just read and collect
affected positions in an open-addressed set. The chunk-cache hit path is kept
small enough to inline, and area queries omit entities whose removal is waiting
for a barrier. See [the cinematic nuke benchmark](docs/cinematic-nuke-2026-09-22.md)
for measurements with the supplied Orbital Strike Cannon and SpeeDeeEntity jars.

The [size-1 Stab Nuke test](docs/stab-nuke-2026-09-22.md) exercises the newer
local Orbital build's 39,122 TNT. Explosion rays skip duplicate set insertions,
and sequential work on a level owner can reuse the private chunk cache. Newly
created short-fuse TNT also request a scheduling probe before their first tick.

Smaller things: each phase's split is rebuilt into buffers kept from tick to
tick rather than reallocated, cells are submitted longest-first so the tail is
short, and the block-entity blacklist is checked against registry objects rather
than against the registry-id *string* `TickingBlockEntity` builds fresh on every
call.

**Walks nobody needed.** Replaying a real client world -- render distance 32,
a 1,880-snowball column, command blocks, an Orbital datapack -- found most of
what was left on the server thread was not work but walks: loops over every
entity or every loaded chunk to answer a question about a few of them.

- `EntityLookup` files entities by type as well as by id, so an
  `@e[type=...]` selector or `getEntities(EntityType, ...)` visits only that
  type. Buckets are insertion-ordered and mirror every add and remove, so a
  type's entities come out in the order the full walk would have produced --
  a selector with a limit and no sort still takes the same ones.
- `ServerChunkCache.hasChunk` answers from the per-thread chunk cache.
  Entities ask it several times a tick each, and under SuperChunk it ends in a
  synchronized map: a parallel pass had every worker queueing on one monitor.
- SpeeDeeEntity's list of collidable entities is built once per level per tick
  and shared, instead of once per worker thread per tick, each walking every
  entity in the level to find the same few boats.
- With `doMobSpawning` off, the mob census `tickChunks` takes over every entity
  is refreshed once a second rather than every tick: only the F3 screen and
  `/debug` read it then. Turning the rule on restores the per-tick census.
- Entity tracking remembers, for the length of one walk, which chunks are in
  entity-ticking range, rather than asking the distance manager per entity.
- On the client, a vanilla snowball's or egg's hit query skips other vanilla
  snowballs and eggs the same way it does inside a server pass. The client
  ticks every snowball too, and a 1,880-snowball column was a
  3.5-million-candidate scan per client tick.

Measurements are in [the client-world report](docs/client-world-2026-09-22.md).

**One explosion, many threads.** A column of stacked TNT is one chain: each
blast moves the next charge and breaks the blocks it will read, so the column
runs on one thread in vanilla's order however many workers are idle. What each
explosion does inside is another matter. Its 1,352 rays only read, so they are
cast in slices on the pool. The per-ray random draws are taken first, in
vanilla's order, and the hits go into the affected set in ray order, so the
same blocks break in the same order. Each ray reads from the chunks the
explosion already holds. Only a block's first hit is kept, which is also what
the serial path keeps. For nearby entities that are not living, exposure is
worked out once on the pool instead of twice in a row. A cached answer is used
only while the entity's box and the level's blocks are unchanged; otherwise
vanilla computes it live. A repeating command block running Orbital's
`stab_power_3` (202 TNT a tick) went from 125 to 38 ms per tick; see
[the stab column report](docs/stab-power-column-2026-09-22.md).
`entities.parallelExplosionRays` turns the pool part off.

After that, most of what was left of each blast was bookkeeping for the
fourteen hundred air blocks its rays pass through. Vanilla puts every one of
them in the affected list, shuffles it and reads each one back in
`finalizeExplosion`. Skein's set now keeps first-insertion order, so the pool's
hits go straight into the list with no set at all; `finalizeExplosion` reads
through the chunks it holds, like the rays; and the 2,700 random draws a blast
takes from one source -- each a compare-and-swap on an `AtomicLong` nobody else
touches -- are taken with the seed in a local, once a self-test at startup has
shown the result is draw for draw the same. With the chunk and tracking
changes below, the column took a third less time than before in interleaved
runs, about 25 ms per tick on a quiet machine; see
[the round-two report](docs/threading-round-2026-09-22.md).

**The chunk tick's two walks.** With a view distance of 32 the chunk tick looks
at some 4,500 chunk holders twice: once for each one's ticking chunk, to build
its loop, and once to broadcast changes that almost none of them have. Each look
is a handful of reads that miss the cache. Both now run on the pool and only
read -- the first calls whatever `getTickingChunk` resolves to, including a mod's
replacement for it, and the loop picks the answers up in order; the second picks
out the holders with something to send. Everything that writes stays where it
was. `randomTicks.parallelChunkScan` turns it off.

**Tracking on the pool.** For nearly every tracked entity, what entity tracking
does belongs to that entity alone: `ServerEntity.sendChanges` compares it with
what was last sent and broadcasts the difference to the players already
watching. That now runs on the pool with the packets collected, and vanilla's
loop runs over only the entities with something left to do, sending each one's
packets in its own order to the same players. Players, item frames, entities
that changed section and vehicles whose passengers changed are still handled
entirely in the loop. `entities.threadedEntityTracking` turns it off.

---

## Config

`config/skein-common.toml`

```toml
[general]
    enabled = true
    adaptive = true            # time each phase both ways, take the cheaper one
    threadCount = 0            # 0 = (processors - 1), clamped to [1, 16]
    useAllCpuThreads = false   # true overrides threadCount and removes the cap; restart required
    crashPolicy = "DISABLE"    # DISABLE | RETHROW
    threadSafeLevelRandom = true
    ignoreModConflicts = false # keep compatibility checks enabled
    logSummaryEveryTicks = 0

[dimensions]
    enabled = true
    blacklist = []             # e.g. ["minecraft:the_end"]

[regions]                      # the geometry every in-level phase is split by
    chunkSize = 8              # raised to at least 4 margins, rounded up to even
    marginBlocks = 32

[entities]
    enabled = true
    threadedProjectileQueries = true # ordered target lists for threaded vanilla snowballs/eggs
    parallelExplosionRays = true     # cast an explosion's rays and exposure checks on the pool
    threadedEntityTracking = true    # work out tracked entities' packets on the pool
    minEntities = 100
    pinPlayers = true
    loadedMarginChunks = 3     # tick on the level thread within this many chunks of one not FULL
    blacklist = ["minecraft:ender_dragon"]

[entities.cluster]             # order by conflict graph instead of by cell
    enabled = true
    reachBlocks = 6.0          # the default when a type gives no "=<blocks>"
    isolationBlocks = 8.0      # keep clustered entities this far from gridded ones
    exactProjectileIsolation = true # vanilla snowballs/eggs: test what they can hit instead
    minEntities = 256
    types = ["minecraft:snowball=0", "minecraft:egg=0",
             "minecraft:item=2", "minecraft:experience_orb=2"]
    inertBlocks = ["minecraft:bubble_column"]   # audited: entityInside writes nothing
```

Set `general.useAllCpuThreads = true` to let Skein use all logical CPU processors
available to Java. It creates one worker per processor minus one, with a minimum
of one worker, because the server thread also runs work. For example, 28 logical
processors give 27 workers plus the server thread. This overrides `threadCount`
and removes the automatic 16-worker cap. Restart the server or reopen the world
after changing either worker setting. Adaptive scheduling still decides when
parallel work is worthwhile; CPU usage depends on the workload.
The [all-CPU and Orbital Strike Cannon verification](docs/all-cpu-orbital-2026-09-22.md)
covers this setting with 27 workers and the supplied cannon jar.
The [CPU diagnosis and configuration report](docs/client-cpu-2026-09-22.md)
explains the SuperChunk/Lithium blockers and measured limits for a cinematic nuke.
An imminent TNT detonation requests a fresh parallel probe so cheap countdown
ticks do not determine how the expensive explosion tick runs. Compatibility
checks and spatial separation still apply.

`entities.threadedProjectileQueries` only applies inside Skein's threaded entity
passes. It skips exact vanilla snowballs and eggs, which cannot be projectile
targets, while retaining section order and the original hit tests, predicates,
damage, and impact events. Modded subclasses remain in the query. Disable this
setting if a mod makes ordinary vanilla snowballs or eggs hittable.
See the [projectile measurements](docs/projectile-query-2026-09-22.md) for the
2,000-snowball benchmark and collision checks.

Compatibility mixins have no config: `SkeinMixinPlugin` adds each one only when
the mod it patches is installed, so a server without that mod never loads it.
See the SpeeDeeEntity entry under Known limits.

```toml

[saving]                       # the periodic autosave, split by chunk
    enabled = false            # off until soaked: see Known limits
    minChunks = 256

[scheduledTicks]
    enabled = false            # exact ordering, but see Known limits
    minTicks = 64
    blacklist = ["minecraft:command_block", "minecraft:chain_command_block",
                 "minecraft:repeating_command_block"]

[randomTicks]
    enabled = true
    minChunks = 16
    parallelChunkScan = true   # the chunk tick's walks over every holder, on the pool

[blockEntities]
    enabled = true
    minTickers = 64
    blacklist = ["minecraft:beacon", "minecraft:conduit",
                 "minecraft:bell", "minecraft:end_gateway"]
```

`marginBlocks` is a **correctness** knob, not a performance knob. It has to be
at least the furthest a single tick reaches -- which for vanilla mobs is follow
range rather than movement speed, and for blocks is a sapling becoming a large
tree. Hence a default of 32 rather than something movement-sized. Raising it
raises the cell size with it, so it costs the number of cells rather than
coverage.

A mob whose follow range is larger than the margin is not left to break the
geometry: it is filed by that reach rather than as a point, so it has to sit its
whole follow range inside its cell and falls to the serial set when no cell has
room. Vanilla has several -- a zombie at 35, a bee or a blaze at 48, an enderman
at 64, a ghast at 100 -- and a zombie alerting other zombies writes a target
into every one of them at that range. Raising the margin buys those mobs back
into the parallel set.

`crashPolicy = DISABLE` logs the throwable, drops that dimension back to serial
ticking for the rest of the session, and keeps the server up. `RETHROW` hands it
to the server thread for a normal vanilla crash report -- use it when you are
trying to *find* something rather than survive it.

Turn phases off in this order if something is wrong: `scheduledTicks` first (it
is off to begin with), then `blockEntities` (most exposed to other mods), then
`entities`, then `randomTicks` (least -- a random tick reaches a few blocks and
touches nothing level-wide), then `dimensions`.

A parallel phase that stays open for more than ten seconds logs every worker's
stack trace, once, at ERROR. A threading mod that hangs is otherwise
indistinguishable from a server that stopped for no reason, so if Skein ever
does freeze, that dump is the thing to send.

## Commands

- `/skein status` — workers, cell geometry, which phases are on, per-phase
  overlap, per-dimension tick times, and any types demoted after failing in
  parallel
- `/skein reload` — re-read the config at the start of the next tick, where nothing
  else is running (thread count applies next start)
- `/skein unquarantine` — return quarantined dimensions, dimensions moved behind
  the barrier for reaching into another one, and entity, block entity, block and
  fluid types demoted after throwing or hanging in parallel, to parallel, and empty
  `config/skein-demoted.txt`

```
Skein
  workers: 16
  cells: 8 chunks, 32-block margin
  phases: dimensions, randomTicks, entities, blockEntities
  dimension overlap: 1.01x  (6.42ms level work in 6.37ms wall)
    random ticks: 8.39x  (19.16ms in 2.28ms) 81 cells over 4 passes, 1152 parallel / 0 serial, 1036 deferred
    entities: 7.06x  (8.42ms in 1.19ms) 74 cells over 4 passes, 1107 parallel / 9 serial, 457 deferred
    block entities: 1.77x  (0.73ms in 0.41ms) 109 cells over 4 passes, 2674 parallel / 0 serial, 0 deferred
```

The numbers to look at are the **overlap** factors, one per phase. Each is that
phase's cell-task CPU time over the wall-clock its barriers were open, and it is
what answers "is this phase earning its barriers?". At `1.00x` a phase is pure
overhead on this world and should be turned off.

`dimension overlap` is the same idea one level up — level tick time over the
wall-clock the dimension barrier was open — and it has a limitation the others
do not: it only exceeds `1.00x` when several dimensions genuinely have work at
the same time, which on a single-dimension server they never do. That is why the
per-phase numbers are reported separately.

---

## What was measured

Dedicated server, NeoForge 21.1.219, i7-14700K, 16 workers. One busy dimension,
1024 force-loaded chunks, `randomTickSpeed 80`, nine connected players spread
across the area, ~1100 ticking entities, 2674 ticking block entities of which 484
are hoppers arranged into 121 closed rings, and 7744 items circulating through
them. Identical scenario driven over RCON, run three times with different phases
enabled.

| in-level phases | overall | overworld |
| --- | --- | --- |
| all serial | 18.23 ms/tick | 18.05 ms/tick |
| random ticks parallel | 10.76 ms/tick | 10.60 ms/tick |
| random ticks + entities + block entities parallel | **7.48 ms/tick** | **7.32 ms/tick** |

Per-phase overlap in the last of those: random ticks **8.4x** (19.2 ms of work in
2.3 ms), entities **7.1x**, block entities 1.8x. Per tick: 1152 chunks, 1107
entities and 2674 block entities in parallel against 9 serial — the nine players,
which are pinned — and ~1500 deferred writes.

### Dense projectiles: the cluster tier

The case the cell split handles worst, and the one it was written for. A 64x64
bubble-column farm -- smaller than one 128-block cell -- holding exactly 5000
snowballs, population pinned every sample so both configs are asked the same
question, nothing else in the world.

| | overall | entity phase wall | entity overlap |
| --- | --- | --- | --- |
| cells only | 19.83 ms/tick | 18.01 ms | 1.01x |
| cluster tier | **4.79 ms/tick** | **1.97 ms** | **14.32x** |

The whole population levels into one to three levels, ~1700 entities wide, over
65 slices. Without the tier the same 5000 entities land in two cells and one
pass: 1.01x, which is the ceiling the geometry has and not a tuning failure.

Correctness, same harness: item totals under heavy merging match vanilla
*exactly* (3994 of 4000 seeded, the missing six being items that drift off the
platform edge -- vanilla with Skein disabled loses the same six), and the
water-and-lava fingerprint over nine basins is identical to vanilla's.

The [single-column regression check](docs/bubble-column-2026-09-22.md) separately
tests 2,000 snowballs in one 1×1 bubble column against the build from before the
cinematic nuke optimizations. It caught and corrected overhead in the new
removed-entity query filter.

### A quiet server

Two thousand hoppers, ninety mobs, a thousand random-ticking chunks: enough
content to be a real server, not enough activity for a barrier to pay for
itself. This is the case the mod used to lose.

| | mean ms/tick |
| --- | --- |
| Skein disabled | 2.17 |
| every phase forced parallel | 2.48 |
| adaptive | **1.99** |

Adaptive beats *both*, because it is not a switch -- the block entity phase
still threads, where it is genuinely 2.2x up, while the entity and random tick
phases fall back to vanilla, where parallel was measured at 17x and 10x worse
respectively. The same build still gets 13.4x on the entity phase with five
thousand snowballs in it.

### The autosave stall

Same server, 1024 force-loaded chunks dirtied continuously, autosave timed
directly at its own call site rather than inferred from tick times:

| | median | worst |
| --- | --- | --- |
| serial | 3 us | **490 ms** |
| split by chunk | 630 us | **101 ms** |

The worst case is the number that matters -- that is the save with real work in
it, and it is 4.8x shorter. The median moves the wrong way because the parallel
path builds a holder list and wakes the pool even when nothing is dirty; at one
autosave every 6000 ticks that floor costs nothing, and it is why the phase has a
`minChunks` gate rather than running for every level unconditionally.

Two busy dimensions, from the earlier round with entity threading alone
(overworld and nether, ~3160 ticking entities each, constant TNT, lightning,
flowing water and fire):

| | serial entities | parallel entities |
| --- | --- | --- |
| Overall | 10.71 ms/tick | **5.31 ms/tick** |
| Dimension overlap | 1.80x | 1.97x |

Correctness checks over the same runs:

- **Scheduled tick order, again after the component change**: the same nine
  basins, serial and parallel, byte-identical.
- **Scheduled tick order**: nine sealed basins, each with five water and five
  lava fronts flowing into each other, built while the game was frozen, stepped
  exactly 400 ticks, then frozen again and counted block by block -- 63 numbers
  covering water, lava, cobblestone, obsidian, stone, floor and walls. Which of
  those blocks appears at a contact point, and where the boundary lands, is
  decided by which fluid tick ran first. Serial twice and parallel twice, and
  all four fingerprints are identical. The parallel runs were confirmed to have
  actually used the phase (six components, ~100 ticks a tick).
- **Items**: 7744 items seeded into 121 hopper rings, counted stack by stack
  before and after. 7744 in, 7744 out, in every configuration — nothing
  duplicated by two threads moving the same item, nothing lost.
- **Entities**: 450 tagged, invulnerable armour stands across two dimensions,
  counted before and after. 450 in, 450 out, with `crashPolicy = RETHROW`.
- **Logs**: no exceptions, no section-index warnings, no tick failures.

Not covered by that: real players doing anything (the nine are headless clients
that log in, hold position and answer keep-alives), and any other mod. A
third-party mod that ticks one dimension from another's tick, or that keeps
unsynchronised per-level state of its own, is the failure mode this cannot test
for.

---

## How much it batches, and where that runs out

One cell is one task, run start to finish on one thread. There is no batching
below that and no stealing inside it, so the number of things that can run at
once is `cells per pass`, and cells come from *area* rather than from how much
work is in them: roughly `loaded area / cell size^2`, split across four passes.
Adding work to the same box adds no cells.

The pathological case is worth knowing precisely, so: primed TNT, summoned from
a datapack function so the whole batch detonates on one tick, on a stone
platform, block drops off.

| | vanilla | Skein | phase overlap | cells/pass |
| --- | --- | --- | --- | --- |
| 20k over 426 blocks | 19.5s | **6.2s** | 10.3x | ~17 |
| 100k over 317 blocks | over 6.5 min, unfinished | **93.5s** | 5.9x | ~15 |
| 20k in one 32-block pile | -- | no gain | **1.00x** | 1 |

The pile is the honest one. Twenty thousand TNT inside a 32-block box is
interior to exactly one cell of one grid, so it runs on one thread and this mod
contributes nothing. That is not a bug to be fixed; it is what "no two things
closer than two margins run at once" means when everything is in the same place.

What that tick actually costs is worth stating too, because it is not what it
looks like. The 20k tick makes **a billion** calls to `getChunk`, all of which
hit the per-thread cache -- no misses, no time on the chunk lock. Scaling the
worker count says the rest:

| workers | CPU time | wall | overlap |
| --- | --- | --- | --- |
| 8 | 44.7s | 5.34s | 8.4x |
| 16 | 51.3s | **4.94s** | 10.4x |
| 24 | 61.3s | 5.55s | 11.1x |

Wall-clock is flat while CPU time climbs. That is a memory-bound workload, not a
scheduling one: eight threads already saturate what the memory system will give,
and the extra threads buy overlap figures rather than seconds. Sixteen is about
the knee on a 14700K; more is worse. Nothing in a threading mod's remit moves
that -- a billion mostly-random block reads is vanilla's explosion algorithm, and
Skein can only decide which threads run it.

---

## What is not threaded, and why

Everything else in the server tick was looked at. These are the ones that were
left alone, with the reason:

- **Mob spawning.** Vanilla interleaves it with random ticking in the same chunk
  loop, so it was the obvious thing to take along, and it is the one that cannot
  go. `NaturalSpawner.SpawnState` and `LocalMobCapCalculator` are running totals
  over the whole level, read and written on every spawn attempt. Splitting them
  means either locking the counters — serialising exactly the thing being
  parallelised — or letting each thread work from a stale count, which changes
  mob farm rates outright. It stays on the level-owning thread.

- **Who sees which entity** (`TrackedEntity.updatePlayers`). It mutates the
  tracker's `seenBy` set, calls into player state and sends spawn packets, so it
  stays in `ChunkMap.tick`'s loop, as does everything for players, item frames
  and changed passengers. The rest of tracking -- `sendChanges` for every other
  entity -- now runs on the pool, with its packets collected and sent afterwards
  in the loop's order (see *Tracking on the pool*).

- **Chunk loading, generation and lighting.** Already off the server thread in
  vanilla, on their own pools. Out of scope, and out of scope by request.

- **Chunk saving.** Already asynchronous in vanilla; what remains on the server
  thread is IO-bound rather than CPU-bound.

- **`entityManager.tick()`**, which loads and unloads entity sections. Structural
  by definition — it is the thing every deferral in this mod exists to protect.

---

## Sharing code with other mods

Skein hooks well over a hundred places in the vanilla tick, and a modpack will
have other mods changing some of the same methods. Three rules keep that
working.

**Hooks chain rather than replace.** Every hook is an `@Inject` or a
MixinExtras wrapper (`@WrapOperation`, `@WrapMethod`); Skein has no
`@Redirect` and no `@Overwrite`. Another mod's wrapper around the same call
chains with Skein's, and another mod's `@Redirect` of it is wrapped rather than
fought over. Where Skein takes a per-thread copy of something only while a
level is ticking in parallel, it hands every other access straight through, so
a mod that reads or sets the same field outside a tick sees vanilla behaviour.

**Loops stay outside, work goes inside.** The three hooks that collect a
vanilla loop's work for a parallel phase -- the entity loop, block-entity
tickers, random-tick chunks -- run at priority 900, below the default.
MixinExtras nests wrappers of one call by priority, so a profiler or throttle
that wraps `TickingBlockEntity.tick()` stays outside Skein's: it runs on the
level's own thread, in loop order, for every ticker, and decides whether a
ticker runs at all before Skein files it.

**A hook that cannot land costs its feature, not the game.** With Mixin's
usual `require = 1`, a mod that rewrote a method Skein hooks stops the game
loading. Skein applies every injector with `require = 0` and checks each one
itself once the class is finished -- after MixinExtras has applied its own
wrappers, and per method rather than in total, which is stricter than
`require`. A hook that did not land switches off exactly the features that need
it, and the log says which hook, in which method, and which other mods'
mixins changed that class:

```
Skein: hook ChunkMapMixin.skein$typeCachePut in ChunkMap.markPosition was not
applied, most likely because another mod rewrote that method. Switching off:
parallel autosave
Skein: 144 hooks checked, 1 missing; switched off: parallel autosave
```

Hooks that come in pairs -- collect here, run there; open a bracket here, close
it there -- are switched off together, so half a mechanism never runs. A hook
whose absence costs nothing but speed is only reported.

Skein's mixins run at priority 1500, above the default 1000, because Mixin
treats a method another mixin has overwritten as belonging to it: an injector
of equal or lower priority may not touch it at all, and that is a hard error,
not a missing hook. Above the default, Skein's hooks go into the other mod's
version of the method, and land or go missing like any other. Two cases still
stop the game at startup, because Mixin refuses before Skein can check
anything: a mod at priority 1500 or more that overwrites a method Skein hooks,
and a mod at any priority above 900 that overwrites one of the three loop
methods -- `ServerLevel.tick`, `Level.tickBlockEntities` and
`ServerChunkCache.tickChunks`. The loop hooks are at 900 on purpose (see
above); a mod that replaces one of those whole methods is replacing the thing
Skein parallelises.

Two features also step aside when they would bypass another mod: pooled entity
tracking runs `sendChanges` off the loop, and pooled explosion rays replace the
ray loop. If another mod's injector sits inside the stretch of code either one
takes over, or another mod overwrote that method, Skein leaves the stretch to
the loop so the other mod's change still applies to every entity and every ray.

`/skein status` shows the same summary under `hooks`, and lists any hook that
is missing.

---

## Known limits

Honest list, dimension threading first:

- **A parallel tick never loads a chunk.** Loading runs the chunk system's
  main-thread work -- tickets, the distance manager's trackers, holder futures,
  and every other chunk's pending FULL promotion, which adds that chunk's
  entities and tick containers -- and a load from a worker left a corrupted
  `LeveledPriorityQueue` behind on the 1.21.11 line on 2026-09-09. So a
  worker's read of a chunk that is not loaded gets vanilla's answer for an
  unloaded chunk if the read is optional and aborts that entity's tick if it is
  required, and `entities.loadedMarginChunks` keeps anything within that many
  chunks of the loaded edge on the level thread, where the load is legal. So do
  a villager about to level up or with no trades rolled yet (a cartographer's
  explorer map searches for a structure up to a hundred chunks away), a fed
  dolphin looking for treasure, an allay whose liked note block is in an
  unloaded chunk, and a creeper, fireball or TNT whose blast
  could reach past that margin. An explosion whose rays could reach an
  unloaded chunk casts them on its own thread, and an unopened loot chest read
  from a worker rolls its loot at the barrier. A refused load ends only the
  asker's tick: it is never reported as that entity or block entity crashing,
  so NeoForge's `removeErroring*` options do not delete it and
  `crashPolicy = RETHROW` does not stop the server over it.

- **Mods whose optimisations assume one thread per level.** Lithium is the
  worked example -- but see *Running with Lithium* below, because these days it
  is a configuration problem rather than a refusal. Four of its features cache
  mutable state on level-scoped objects, and every one fails as silence rather
  than as a crash. `alloc.chunk_random` returns the same mutable `BlockPos` to every
  caller in the chunk loop, which is how blocks end up placed in the wrong chunk
  and how a lava tick sets fire to somewhere it never touched.
  `util.world_border_listener` registers every block entity in a `WeakHashMap` on
  the world border; two threads in an unsynchronised `HashMap` corrupt a bucket
  chain and spin forever. `world.chunk_access` puts a chunk cache on the chunk
  source. The hopper inventory tracker creates entity sections from a
  block-entity tick. Four was enough to stop looking for a fifth, so Skein
  detects the mod rather than the feature and turns the in-level phases off.
  Dimension threading keeps running — one thread per level is exactly the
  assumption those caches are built on. `general.ignoreModConflicts` overrides
  it if you have switched the offending features off yourself.
  The same shape shows up in any mod that memoises per level — but a mod is not
  always worth switching off for it. **SpeeDeeEntity** is the worked example of
  the other outcome. Its OFEM collision optimisation hangs a
  `Long2ByteOpenHashMap` of "is this region clear" answers off the `Level` and
  reads and writes it from `Entity.move`; two Skein workers inside `rehash`
  throw `ArrayIndexOutOfBoundsException` out of fastutil, and a table that
  survives the race in a corrupt state answers `CLEAR` for solid blocks.
  Disabling it is the wrong trade, though, because what it optimises is fast
  entities and fast entities are exactly what the entity phase runs in
  parallel. Skein loads optional compatibility mixins when SpeeDeeEntity is
  installed and gives each calling thread a complete `TickCache` owned by the
  level. This covers both the legacy clearance map and newer builds' collider
  lists, including the index used by vanilla's slow collision path. Detection
  checks whether the installed jar contains `EntityColliders`, because both
  layouts have shipped under version 1.0.1.

  Clearance answers expire when game time changes and before/after a block is
  written through `LevelChunk.setBlockState`. An atomic revision tells each
  worker to empty its own map on its next lookup; no worker clears another's
  map. This preserves invalidation during the same tick, including direct chunk
  writes and frozen game time. SpeeDeeEntity still owns the sweep, cache keys,
  collision math, and collider-index epoch rules. Caches live on the level so
  their entity references can be collected with an unloaded world.

  No compatibility config is needed. The bridge is tested with legacy 1.0.1
  and indexed 1.0.2 on NeoForge 1.21.1; see
  [the compatibility verification](docs/speedee-compatibility-2026-09-22.md).

  This is the shape a compat mixin should take. It is worth doing when the
  conflict is one identifiable field rather than a design assumption spread
  across two hundred mixins, which is why Lithium gets a switch and this gets a
  patch. The general lesson still holds: a cache on a level-scoped object is a
  data race here, and the ones that hurt most are the ones that do not crash.
- **Mods that assert the level ticks on the server thread.** Some chunk mods
  ship C2ME's "async catchers": an injection at the head of
  `ServerChunkCache.tick` that throws `ConcurrentModificationException("Async
  ticking server chunk manager")` when the caller is not the thread the world
  was loaded on. That check and this mod disagree about what is legitimate --
  Skein's whole ownership model exists to replace exactly that identity test --
  and the catcher wins, because it throws first. Every parallel dimension is
  quarantined on its first tick. Either turn `dimensions.enabled = false`, or
  use a compatibility bridge which verifies the worker owns that exact level
  during the dimension phase. A worker's class alone does not establish
  exclusive ownership: it can also be running an entity cell or save job.
- **Ticks that reach into another dimension.** Vanilla does this in a few
  places and mods in more: an ender pearl thrown through a portal teleports its
  thrower from the pearl's dimension, a command block runs `execute in` or
  `/tp` into another dimension, a mod's `LevelTickEvent` listener reaches for
  `server.overworld()`. While dimensions tick in parallel the other one is
  being ticked by another thread at that moment. Skein parks what it can see:
  every teleport and dimension change, pearl landings, spawns into another
  level and chunk tickets all wait for the dimension barrier. A chunk request
  into another dimension cannot be served at all -- vanilla's own off-thread
  path would wait forever for a thread that is busy ticking -- so that one
  request fails, the log says which dimension reached where, and the asking
  dimension ticks after the others from then on. What Skein cannot see is a mod
  writing through a chunk reference it already holds; blacklist the dimension
  if you suspect one.
- **`perWorldTickTimes`** is written under a lock, so `/neoforge tps` stays
  correct — but the numbers are now overlapping wall-clock, not additive.
- **Profiling.** `ActiveProfiler` is a plain `ArrayList` and `HashMap` behind an
  interface. Rather than feed the workers a no-op profiler and hand back a
  profile that quietly omits most of the server, Skein stays serial for as long
  as anyone is profiling (`/debug start`).

The in-level phases, additionally:

- **Tick order changes.** Vanilla ticks entities in insertion order and chunks in
  a shuffled one; Skein runs cells concurrently, in four passes, with pinned work
  last. Anything depending on exact ordering within a tick will drift. The split
  itself is deterministic — the same work in the same places produces the same
  passes, the same cells and the same replay order — but it is not vanilla's
  order.
- **The cluster tier runs wholly before the grid tier.** The two use different
  conflict tests, so nothing from one may be in flight while the other runs. A
  clustered entity therefore meets a gridded one at its pre-tick position --
  the same class of reordering the four passes already introduce between cells,
  but worth stating separately. Within the tier, any pair that can actually
  interact still runs in vanilla's list order.
- **A block that reacts to a clustered entity reacts at the barrier.** Pressure
  plates, tripwires, target blocks and anything else whose `entityInside` or
  `onProjectileHit` writes are parked and replayed in task order when the entity
  that triggered them is in the cluster tier. Same tick, one pass of latency.
  Blocks that only read, or that only push the entity, are untouched.
- **Game events arrive at the barrier**, not mid-pass, when a parallel phase is
  open. A sculk sensor sees the vibration in the same tick and in deterministic
  order, one pass later than vanilla. This is what lets a dense farm level at
  all; see the cluster tier above.
- **Spawning now runs before random ticking** rather than interleaved with it,
  chunk by chunk. A spawn attempt sees the world one tick before a block grown by
  a random tick in the same loop, instead of after it.
- **Path invalidation is one pass late** for mobs in the same pass as the block
  that changed. See the navigation sweep above.
- **`scheduledTicks` has an open report against it**: a world containing 0-tick
  redstone froze with it on. The component split is exactly order-preserving on
  paper and reproduces vanilla's fluid interactions block for block in testing,
  and one vanilla cause of exactly that symptom has since been found and fixed
  (`RECENT_TOGGLES`, above) -- but the report has not been reproduced since, so
  the phase stays off by default until it has been.
- **Mods with unsynchronised per-level or static state.** The pieces vanilla and
  NeoForge have are handled; a mod that keeps its own is not, and Skein has
  no way to detect it. This is the one to keep in mind before turning the
  in-level phases on next to a large modpack, and `blockEntities` is where it
  will bite first.
- **Reach beyond the margin.** The margin is what the geometry rests on. A mob
  that acquires targets further than `marginBlocks` away is handled -- it is
  filed by its follow range and ticks serially if that does not fit -- but a
  machine that edits blocks across the world from its block-entity tick, or
  anything else whose reach is not declared as an attribute, breaks the
  argument. Raise the margin (the cell size follows automatically) or blacklist
  the block entity type.

## When this does not help

- The server is IO- or chunk-generation-bound rather than tick-bound — those
  already run on their own threads in vanilla.
- Nothing is loaded. Below each phase's minimum, the passes decline to open and
  the level ticks exactly as vanilla does.
- Nobody is online. Random ticks and mob spawning are gated on a player being
  within 128 blocks, so an empty server does almost nothing to parallelise.

## Building

```
./gradlew build          # jar in build/libs/
./gradlew runServer      # dedicated server, run/
./gradlew coreTest       # reference and concurrency checks (also part of build)
./gradlew coreBenchmark  # warmed scheduling microbenchmarks
```

The core checks compile the production scheduling algorithms against small
test-only Minecraft stand-ins. They check geometry, ordering and real worker
barriers without starting a server; they do not replace in-game compatibility
testing. The stand-ins are kept out of the mod jar.
