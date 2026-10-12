# Redstone parity -- 2026-10-11

The scheduled-tick phase (`scheduledTicks.enabled`) runs block and fluid ticks -- repeaters,
comparators, observers, torches, flowing water, falling sand -- on the pool, split into groups of
ticks close enough to affect each other. Each group already ran in vanilla's order. This round makes
the phase as a whole indistinguishable from vanilla: the same blocks, the same entities, and the same
pending ticks in the same order, on every tick.

## What was not vanilla

A random-circuit fuzzer (below) compared runs with and without Skein. The blocks matched, but the
order of the pending ticks did not, from the second tick on, and it differed from one Skein run to
the next. That order is what the next tick runs in, so it is where a contraption that depends on
sub-tick timing goes wrong first. Reading the phase turned up these causes:

1. **Sub-tick numbers came from the threads' race.** Every scheduled tick is stamped with the level's
   next sub-tick number, and among ticks due on the same game tick at the same priority that number
   is the run order. Workers drew from the counter side by side, so ticks scheduled by different
   groups were numbered in whatever order the threads reached it.
2. **Parked writes were replayed group by group.** Block events, entity spawns, new block-entity
   tickers and chunk tickets were applied to the level's shared queues in group order, not in the
   order vanilla would have made them.
3. **A parked tick was invisible to `hasScheduledTick`.** Several blocks branch on it: a target block
   hit twice in one tick keeps the first hit's power only because the second hit sees the first one's
   tick; an observer placed by a piston turns itself off at once if it sees no pending tick, which is
   what breaks flying machines and 0-tick contraptions; pointed dripstone, sculk sensors, tripwire and
   lightning rods ask too. This applied to every parallel phase, not just this one.
4. **A parked entity was invisible to the rest of its group.** A pressure plate whose tick came after
   a falling block's in the same group did not see the new entity.
5. **Game events were heard at the end of the phase.** A sculk sensor decides whether it can hear a
   vibration from its own state at that moment, so hearing it later, after its own tick had run, could
   change the answer.
6. **Tripwire reaches 42 blocks.** A hook's tick sets the hook at the far end of its string, further
   than the 32-block margin the split assumes.
7. **A tick near the edge of loaded terrain could be cut short.** A worker may not load a chunk, so a
   tick that read past the edge was stopped halfway through.

## What changed

- **Writes are keyed by rank and merged.** Each group's parked writes are stamped with the rank (the
  position in vanilla's run order) of the tick that made them. The coordinator walks the ranks in
  order and applies each tick's writes, so the queues are left exactly as vanilla leaves them
  (`DeferredBuffer.key`, `applyKey`; `OrderedGroups.merge`).
- **Sub-tick numbers are drawn when a tick is applied.** A tick created inside a parallel pass carries
  a placeholder and is numbered when its parked schedule is applied (`SubTickOrder`). In this phase
  that is in vanilla's order, so the numbers are the very ones vanilla would draw. In the grid phases
  they follow the replay order, which is at least the same every run.
- **Parked ticks stay visible.** Each buffer keeps the ticks parked in it, and `hasScheduledTick`
  answers from them on that thread (`LevelTicksMixin`). This applies in every parallel phase.
- **A group stops where the rest could see a parked write.** Writes are either order-only (block
  events, client updates, new tickers, chunk tickets, scheduled ticks) or not (spawns, entity removals,
  structure placement, loot rolls, game events that reach past the margin). After a tick that parks
  one of the second kind, the group stops, and the coordinator runs the rest of that group itself, each
  tick at its own rank in the merge, with everything vanilla would have applied before it applied.
- **Game events are heard on the spot** when every listener they can reach is within the margin around
  the tick that fired them, which is where that tick's own writes are. Others are parked as above.
- **Long reach is filed as long reach.** A tripwire hook's or string's tick is filed with points 42
  blocks out along each axis, so anything those could touch joins its group (`ClusterPartition.add`).
- **Groups near unloaded terrain run on the coordinator**, at their ranks in the merge, where a chunk
  load is the same synchronous one vanilla does (`LoadedAround.test` over the group's ticks).
- **Small groups share a task.** Groups are packed into a few tasks per thread, largest first, each
  onto the task with the least work, rather than handed out one by one.

## How it was tested

`RedstoneParity` (`-PredstoneParity`, 26.2 NeoForge and 1.21.1 NeoForge compat tests) builds 48 sealed
boxes at y=120 in sixteen groups of three: a group's boxes share a component, the groups are 160
blocks apart. Each box holds a seeded random circuit started by observer clocks. The `basic` kit has
dust, repeaters of every delay, comparators in both modes, observers, torches, pistons and sticky
pistons, slime, honey, lamps, note blocks, targets, trapdoors and redstone blocks. The `full` kit
(`-PparityKit=full`) adds sand, gravel and concrete powder, rails of every kind with minecarts, armor
stands, pressure plates, tripwire lines, sculk sensors, hoppers under stocked chests, fence gates,
copper bulbs, levers and water. After every tick it hashes each box's blocks, comparator outputs,
moving pistons and container contents, every entity in the boxes in the order the level keeps them,
and every pending block and fluid tick in the order they will run. A run is compared with a vanilla
run of the same seed, line by line, for 600 ticks.

Nothing in the kits draws from the level's random source or depends on entity ids, because vanilla
does not reproduce those between runs (see below); with them, two vanilla runs already differ.

`-PparityStops` makes every group stop after its first tick, so the coordinator finishes nearly every
group in the merge. That path is otherwise rare (a spawn or a far game event), and this exercises it
on tens of thousands of ticks a run.

Before this round, with only the scheduled-tick phase parallel, the blocks matched for 600 ticks
but the pending-tick order differed from the second tick, and differed between two Skein runs too.
After it, every comparison was identical on all three counts -- blocks, entities, pending ticks in
order -- on every one of 600 ticks:

| runs | seeds | kit | threads | ticks finished on the coordinator |
| --- | --- | --- | --- | --- |
| 12 | 1-6 | basic and full | 8 | 0-1,630 per run (groups near not-yet-loaded chunks at the start) |
| 3 | 1, 2, 3 | full, full, basic | 8, every group stopping after one tick | 57,248-79,718 per run |
| 4 | 1, 2 | full | 2 and 16 | 0-144 per run |

The phase was parallel throughout: 16 groups a tick, up to 5x overlap in the fuzzer's own status
lines. Two vanilla runs of each seed and kit were identical as well.

On the Orbital Skybender TNT cannon (`bench/osc.py`), with the phase on: a 5,070-TNT payload stepped
exactly 1,997 ticks ends with every TNT where vanilla leaves it, with the same fuses, and a real-time
build of the 30,660-TNT payload, with every phase forced parallel and a decoy observer clock 400
blocks away so that the cannon is one component of several, ran eight minutes without a stall. That
is a world of the 0-tick redstone the open hang report is about; it did not reproduce.

## Block entities

With the scheduled-tick phase exact, the fuzzer still drifted from vanilla with `blockEntities` on,
from its third tick. That phase split by grid, and the grid can put two block entities a few blocks
apart into different passes: a moving piston finishing beside an observer, two hoppers of one clock.
They then ticked in pass order rather than vanilla's.

The phase can now group block entities by what each can actually reach (`BlockEntityReach`): a hopper
reads the container above it and the items over it and writes the container it faces, a double
chest's other half two blocks out, whose comparators are two further; a furnace, brewing stand,
campfire, crafter or shulker box touches itself and its comparators. Anything else -- a moving piston,
a daylight detector, a sculk sensor, a spawner, any modded block entity -- reaches the margin. Two are
joined when their reaches plus a chunk span the gap between them (`ClusterPartition.addLinking`),
and the groups run side by side through the same rank-keyed merge as scheduled ticks
(`OrderedGroups`, shared by both phases now). A ring of hoppers a couple of dozen blocks from the next
is a group of its own.

The block entities that always tick on the level's own thread (`blockEntities.blacklist`: beacons,
conduits, bells, end gateways) are grouped by what they do to blocks and the mobs beside them, which is
little -- they are pinned for what they do to players and raiders tens of blocks away, which no other
block entity touches. A group holding one runs on the coordinator at its ranks, as does a group with
a block entity whose own reach comes near unloaded terrain. A first version gave them the margin, and
in the fuzzer's world a village bell pulled a box group of redstone onto the coordinator with it.

A group bigger than `blockEntities.vanillaOrderLimit` -- a dense build where everything is in reach of
something -- is split by the grid as before. The limit is 0 by default, which keeps the grid
everywhere; see below for why.

With `vanillaOrderLimit = 512` and the fuzzer's `full` kit (seed 7), every comparison was identical on
all three counts for 600 ticks: block entities alone, block entities with scheduled ticks, and both
with every group stopping after one item (541,311 items finished on the coordinator). 1.21.1 NeoForge
matched as well.

What it costs was measured in one server rather than across two (`BlockEntityOrderBenchmark`,
`-PbeOrderBench`): the machine running the tests was shared and busy, and two dedicated-server legs a
few minutes apart saw loads of 10 and 35. The benchmark world holds 648 hopper rings in 81 stacks, 64
fields of 36 empty collector hoppers that look for items every tick, and a solid 40x40 block of
hoppers that takes the grid fallback, and the server switches every 20 ticks between the groups, the
grid and the phase switched off, so all three see the same load. Over 900 ticks each:

| block entities | median overworld tick |
| --- | --- |
| groups in vanilla's order | 3.53 ms |
| grid | 3.68 ms |
| off (serial) | 2.52 ms |

The first version of the groups cost about 0.4 ms a tick more than the grid at this size: every
ticker was placed in the grid as well as grouped, and looked up its reach several times. Now only
what is left for the grid is placed in it, and a ticker's reach is looked up when it fires a game
event, which is rare. Hoppers this cheap are not worth running in parallel at all on that machine --
the phase governor would pick serial for them in either mode -- but where the phase does pay, the
groups cost no more than the grid did.

That holds except where the work is one dense contraption. The Orbital Skybender test world builds a 30,660-TNT
payload with dupers whose moving pistons push stacks of thousands of TNT, and every one of those
pistons is within reach of the others: in vanilla's order they are one group, on one thread. Run in
real time for eight minutes, back to back with the 2026-10-10 build:

| build | mean tick | TNT built | block entities, as the governor last timed them |
| --- | --- | --- | --- |
| 2026-10-10 (grid) | 30.1 ms | 30,660 of 30,660 | 3.5 ms in parallel, 73.9 ms serial |
| groups in vanilla's order | 55.2 ms | 28,111 | 131 ms in groups, 98 ms serial |

The grid, meanwhile, already leaves that cannon exactly where vanilla does: stepped 1,997 ticks, every
build of this week ends with the same 5,070 TNT in the same places with the same fuses. So ordering
block entities is a choice, not the default: `vanillaOrderLimit = 512` orders all but the densest
builds, for a server that would rather have exact redstone than cannon throughput.

## The default path

Nothing above is on by default except what every parallel phase shares: parked ticks visible to
`hasScheduledTick`, sub-tick numbers drawn when a parked tick is applied, and order-only writes not
stopping anything. Against the 2026-10-10 build, on the dedicated-server bench with Skein's default
config (three back-to-back pairs per scenario, load 7 to 10), the median pair ratios were 1.007 for
the mixed scenario, 0.82 for the quiet one and 1.04 for the hopper rings, whose pairs spread from 0.67
to 1.30. The Orbital Skybender build ran at 28.1 ms a tick against that build's 30.1 ms, and built all
30,660 TNT.

## On by default

Since 2026-10-12 the scheduled-tick phase is on by default, and timed both ways by the phase governor
like the others: when a level's ticks do not pay for the barrier, vanilla's loop runs them. Both
paths give the same result, so the governor switching between them mid-game changes nothing but
time. A server whose `config/skein-common.toml` was written before keeps the `enabled = false` it
has; set it to `true` there.

## What is still not vanilla

- **Random draws.** Inside a parallel phase, `level.random` is drawn per thread. Vanilla seeds it from
  the clock when the server starts, so no two vanilla runs draw the same values either: a dispenser's
  slot, a primed TNT's nudge and fire spread are random in both, from different streams.
- **Entity ids.** An entity gets its id from a global counter when it is constructed, on whichever
  thread constructs it. Vanilla's ids already differ from run to run, and so does everything keyed on
  them, such as which ticks a resting item moves on.
- **Entities.** The entity phase still splits by grid outside the cluster tier's admitted types, so
  two entities a few blocks apart can tick in pass order: a minecart and a TNT, say. With every phase
  on, the fuzzer's blocks and entities still matched vanilla for 600 ticks, but the ticks entities
  scheduled were numbered in pass order from the first tick. Ordering them needs the cluster tier's
  treatment -- a reach for each entity, including how far it moves -- for more types.
- **A bell against moving pistons.** A bell makes raiders 48 blocks away glow, further than its
  group reaches, so a piston pushing such a raider in another group the same tick can come first or
  second.
- **Within the tick that made them,** a structure block's placement, a navigation sweep, and a game
  event that reaches past the margin land at the end of that tick rather than in its middle. The rest
  of the group then runs in order after them.
