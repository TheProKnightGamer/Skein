# A player beside the column — 2026-09-22

On the client instance, 1,880 snowballs in one 1×1 bubble column ran at about
50 ms per tick with Skein installed. The benchmark fixture for the same column
runs in 1–2 ms. The difference was the player standing next to the column.

## Cause

Before the cluster tier runs, `ClusterPass.isolate` hands back to the grid every
clustered entity within `isolationBlocks` (8) of any gridded entity, measured
centre to centre. The player stood at (774.45, 77.5, −327.3), 1.8 blocks from the
column at (774.5, −325.5). Every snowball was within 8 blocks of the player, so
the whole column went back to the grid. The grid files a column into a single
cell, and a single cell runs on one thread. A JFR recording of the live client
showed snowball ticks under `ParallelPass.runCell` on one worker and
`ClusterPass.isolate` on the coordinator. The dedicated-server benchmark has
no players, which is why it never showed this.

## Change

Vanilla `Snowball` and `ThrownEgg` (exact classes) are now tested against what
they can actually hit this tick. A snowball or egg can write to an entity outside the tier only by
hitting it, and `ProjectileUtil.getHitResultOnMoveVector` hits only an entity
whose hitbox, inflated by 0.3, crosses the segment from the projectile's
position along its motion. The segment's box is padded by 0.3 plus a 0.25-block
allowance and checked against every gridded entity's hitbox, with multipart
parts merged into their parent's box. Portal travel is already parked at the
barrier, and outsiders do not tick until the tier closes. Fluid pushing is the
only thing that can change the segment before the hit test, and it moves it by
at most `motionScale` (0.014 for water).

Other clustered types and modded subclasses keep the radius test.
`entities.cluster.exactProjectileIsolation = false` restores the radius for everything.

## Verification

`IsolationVerification` (real server, SpeeDeeEntity installed) builds 1,500
snowballs, eggs and a subclass among an armor stand, a cow and an ender dragon
with a displaced part. For every projectile kept in the tier, vanilla's own
`getHitResultOnMoveVector`, restricted to outsiders, must find no target. It
passed: 303 projectiles that vanilla hits were demoted, and 436 kept projectiles
were within 8 blocks of an outsider but unable to hit it. The 400-snowball
column beside a bystander keeps every snowball with the change, and loses every
one with it turned off. The other verification suites and `coreTest` also passed.

## Measurement

`BubbleBenchmark` with `-PbubbleBystander` adds an armor stand where the player
stood: one glass block away, 1.8 blocks from the column centre. 2,000 snowballs,
200 warmup + 400 measured ticks, 27 workers, SpeeDeeEntity 1.0.2, fresh JVM per
run, one server at a time, run order before/after/after/before.

| Build | Run 1 | Run 2 | Clustered entity ticks |
| --- | ---: | ---: | ---: |
| Before (`c8ea3954…`) | 38.533 ms | 36.264 ms | 0 |
| After (`88cc83d6…`) | 1.295 ms | 1.355 ms | 800,000 |

That is **96.5% less tick time** in this fixture. Without the bystander the two
builds measured 0.905 ms (before) and 0.952 ms (after), one run each. There,
nothing but the snowballs is nearby, the isolation returns before building
anything, and the difference is run-to-run noise.

```sh
./gradlew runSpeedeeTest --offline -PbubbleBenchmark -PbubbleBystander \
  -PspeedeeTestJar=/path/to/speedeeentity-neoforge-1.21.1-1.0.2.jar \
  -PbenchmarkSkeinJar=build/libs/skein-1.0.0.jar
```

These are local measurements of the entity phase in a fixture. Client tick time also
includes chunk ticking, chunk sending, entity tracking and command blocks, which
this change does not touch.
