# TNT cannons -- 2026-10-10

A server building an Orbital Skybender payload (a TNT cannon whose dupers stack tens of
thousands of primed TNT on the same spot) fell further and further behind with Skein 1.0.1:
`/skein status` put 380ms of every tick in block entities, and parallel ticking did not help.
This round found out why, and fixed it in all eight lines.

## How it was measured

`bench/osc.py` builds one payload of size N on a copy of the cannon's save, on the 26.2
NeoForge bench server. It kick-starts the cannon's portal chunk loaders and enters N on the
loader rails the way the cannon's own memory read-out does, with the clock frozen so the pulses
land on exact ticks, then either runs in real time sampling `/tick query`, or steps an exact
number of ticks and dumps every primed TNT's position and fuse. The world is the user's
"Skybender Test dual-lane builder v4": two 6+6 lanes, 24 TNT per counter step, so N=20 is
5,070 TNT and N=50 is 30,660.

## What the time was

A Flight Recorder profile of an N=20 build put 53% of the server thread in one method,
`EntitySection.getEntities`, by way of:

```
PistonMovingBlockEntity.tick -> moveCollidedEntities -> (for each entity) moveEntityByPiston
  -> Entity.move -> Entity.collide -> getEntityCollisions -> Level.getEntities -> EntitySection.getEntities
```

The dupers drop each new TNT on its stack, frozen in a chunk that does not entity-tick, and the
builder's pistons push the stacks back and forth with every lane stroke (and the walkers and the
final merge carry them afterwards). A push moves every TNT in its path, and every one of those
moves asks the level which entities the TNT could collide with. The answer is always none -- a
primed TNT cannot be collided with -- but vanilla finds that out by testing every entity in the
box, and every other TNT in the stack is in the box. One push of a stack of N costs N x N tests.

## What changed

1. **Collision lookups only look at what can be collided with** (`entities.collisionIndex`,
   `CollisionQueries`). Each entity section also keeps the list of its members whose class can
   ever be collided with (overrides `canBeCollidedWith`: boats, shulkers, happy ghasts, and
   modded entities that say so) or that carry parts. A collision lookup from an entity using
   `Entity`'s own `canCollideWith` walks that list instead of the whole section; the query's own
   predicate still decides, so the answer is vanilla's, in vanilla's order. The list is kept up to
   date as members come and go, so a stack crossing into a section costs nothing to keep it
   current. Lithium solves the same problem with per-class section lists built on first query,
   which Skein has to switch off because two workers making the first query at once corrupt them;
   this is the thread-safe equivalent. If another mod changes `Entity.canBeCollidedWith` or
   `canCollideWith` themselves, the hook audit switches the feature off.
2. **A big push is spread over the pool** (`entities.parallelPistonPushes`, `PistonPushes`). Inside
   one push the entities are independent: each one's distance is worked out from its own box,
   its move reads blocks and writes only itself, and nothing in the batch can be collided with.
   The loop runs as vanilla wrote it, except that each entity's `moveEntityByPiston` (and the
   `fixEntityWithinPistonBase` that may follow it) is collected, then run on the workers in
   contiguous slices with Skein's usual parking: section moves and block reactions are replayed
   in entity order at the end of the push. Only for 256 or more exact vanilla primed TNT or
   falling blocks, none riding anything, every chunk around them loaded, on the thread that owns
   the level outside any pass.

On 1.20.1 the collision lookup is marked from `Entity.collide`: that version's mixin library
cannot inject into `EntityGetter`'s default method.

## Results

Real time, nine and a half minutes each, 26.2 NeoForge, N=50 (30,660 TNT):

| build | TNT built | mean tick | late build (400-500 s) | worst 100-tick P99 |
| --- | --- | --- | --- | --- |
| 1.0.1 | 11,651 (38%) | 288 ms | 446 ms | 1,455 ms |
| + collision index | 30,660 | 54 ms | 83 ms | 424 ms |
| + parallel pushes | 30,660, done at 447 s | 30 ms | 35 ms | 343 ms |

At N=20 (5,070 TNT), the collision index alone: build 29.4 -> 8.3 ms mean, the stack moves and
merge 89.9 -> 10.5 ms, their worst ticks 1,195 -> 84 ms.

Correctness: stepped exactly 1,997 ticks from the same inputs, vanilla, 1.0.1 and both new builds
leave all 5,070 TNT in the same places with the same fuses (5,040 at fuse 80, 20 at 72, 9 at 71,
merged at 848.0, 166.59, 832.0, plus the x-walker's single TNT at fuse 1). New in-game checks on
1.21.1 NeoForge: every collision lookup in a mixed crowd (a 300-TNT stack, boats, minecarts,
shulkers, items) matches vanilla shape for shape with the index on; a piston pushing a 600-TNT
stack leaves it in the same place with the push on the pool as with the vanilla loop. Every line
starts with every hook applied.

## What is left

With both fixes in, the next cost at N=50 is vanilla's own: when a stack is carried into another
entity section, each TNT is removed from the old section's lists with `ArrayList.remove`, a scan
that walks past every other stack sharing that section. That is about a third of what remains on
the server thread during the build, and fixing it means changing how `ClassInstanceMultiMap`
removes entries -- which Lithium also patches -- so it is left for a round of its own.
