# Threaded projectile queries — 2026-09-22

Ordinary snowballs and eggs cannot be hit by other ordinary thrown projectiles.
A dense bubble column nevertheless made every snowball scan every neighbour
before vanilla's predicate rejected them. Skein now reuses an ordered,
immutable list of potentially hittable entities for each entity section during
its threaded passes. Exact vanilla snowballs and eggs are omitted; all other
classes, including modded subclasses, remain in their original order.

The first querying worker builds the list. No main-thread preprocessing pass,
global index, or lock was added. Section additions/removals invalidate it.
Workers still read live bounds, removal state, and eligibility. Markers travel
with the specific query, so nested unrelated queries retain their own behavior.
Minecraft still performs the original exclusion, predicates, multipart queries,
nearest-hit selection, block clipping, damage, and impact events.

Only exact vanilla snowball/egg shooters within a Skein-owned deferred entity
pass use this path. Serial ticks, other projectiles, and custom subclasses keep
the normal path. `entities.threadedProjectileQueries` defaults to true; turn it
off if another mod makes ordinary vanilla snowballs or eggs hittable. This
preserves vanilla collision rules, not arbitrary rewrites of those rules by mods.

## Measurement

Two fresh JVMs per build, each with 2,000 snowballs in one 1×1 bubble column,
200 warmup and 400 measured ticks, 27 workers, adaptive scheduling, 4 GiB heap,
SpeeDeeEntity 1.0.2, NeoForge 21.1.219, Java 21, Core i7-14700K. Each snowball
bounces naturally; none is teleported, reset, or replaced during measurement.

| Build | Mean tick, run 1 | Mean tick, run 2 | Combined mean |
| --- | ---: | ---: | ---: |
| Previous build | 4.930 ms | 5.243 ms | 5.086 ms |
| Ordered projectile targets | 1.760 ms | 1.309 ms | 1.535 ms |

That is **69.8% less tick time**, or **3.31× faster** in this fixture. All 2,000
snowballs remained alive and ticked exactly once per frame. The recorded height
and vertical velocity matched across all 1,600 samples. Both new runs dispatched
all 800,000 measured entity ticks through the cluster scheduler. Each baseline
had one adaptive serial probe. These are local samples, not a universal speedup
or a whole-client FPS measurement. Raw rows: [CSV](projectile-query-2026-09-22.csv).

The two new jars differ by the author/package rename; their query algorithms
are identical. Baseline SHA-256:
`68e4ab5bb8daaaa600427da69f54776207da83f5047d5147486eaa7dce358178`.
New run 1: `080bca518c350550c2dfbce5191c1846253316273ae7ffc9e6d8ffd490896c27`.
New run 2: `b79076c8655e37a5d52e4d2e938e3dd79657314925d2ff5c6e43f1b61f0292cd`.
Subsequent logging changes do not change the query implementation.

An additional isolated run with the installed SuperChunk 0.3.0 and Lithium
0.15.4, with the conflicting Lithium options disabled and dimension threading
off, passed at 2.441 ms mean. It dispatched all 800,000 measured snowball ticks
through the cluster scheduler. This has a different mod configuration and is
not included in the before/after averages.
The later configuration also disables Lithium's unsafe entity-group cache
after the collision stress test exposed a race. With that correction, the final
jar measured **2.643 ms** mean (2.475 ms median, 3.115 ms p95), all 2,000
snowballs retained, and the same recorded trajectory as the baseline. It
dispatched 798,000 entity ticks through the cluster scheduler, with one adaptive
serial probe. [Final combined-mod rows](client-bubble-2026-09-22.csv).

## Verification

Real transformed Minecraft classes passed ordered-candidate and hit-result
comparisons; dynamic pickability; additions, removals, same-section movement
and section transfers; owner immunity; custom shooter fallback; unrelated and
nested queries; equal-distance ties; multipart targets; block clipping; actual
snowball damage; and canceled impact events. Another 512 comparisons ran
concurrently on Skein workers. The suite passed with and without SpeeDeeEntity.
The existing 32 seeded explosion comparisons and scheduling/barrier tests passed.

```bash
./gradlew build --offline
./gradlew runSpeedeeTest --offline -PspeedeeTestJar=none
./gradlew runSpeedeeTest --offline \
  -PspeedeeTestJar=/path/to/mods/speedeeentity-neoforge-1.21.1-1.0.2.jar
./gradlew runSpeedeeTest --offline -PbubbleBenchmark \
  -PspeedeeTestJar=/path/to/mods/speedeeentity-neoforge-1.21.1-1.0.2.jar \
  -PbenchmarkSkeinJar=build/libs/skein-1.0.0.jar
```

Optional `extraTestModsDir`, `testConfigDir`, and `testDirectory` Gradle
properties let the same fixture load actual mod-folder bootstrap jars and
staged configurations in a separate world. Test classes stay out of the release jar.
