# SpeeDeeEntity compatibility — 2026-09-22

Skein's optional bridge supports the legacy clearance cache and the newer
SpeeDeeEntity implementation with an entity collider index on NeoForge 1.21.1.
Install both mods normally; no compatibility setting is required.

## Change

The previous bridge redirected `sweepIsClear` map accesses. Newer builds added
a `TickCache` parameter to that method and a shared mutable collider list,
including queries from the slow movement path. The old injection signature
could not cover that layout, and making just the clearance map private would
still leave the list racing between workers.

The bridge now substitutes the complete `TickCache` at `OFEMUtil.tickCache`.
On builds containing `EntityColliders`, it also supplies that thread's cache
when the collider candidates are queried. SpeeDeeEntity retains its original
sweep, collision math, cache keys and collider-index epoch handling. The optional
index mixin is selected by inspecting the installed jar, since both layouts
exist under the 1.0.1 version label.

Each level owns one cache per calling thread. A level's collider references
therefore do not become roots through persistent worker thread locals.
Clearance maps reset on game-time changes and on a level-local atomic revision.
`LevelChunk.setBlockState` advances that revision before and after a write,
covering normal block placement and direct chunk writes. Workers clear their
own maps on the next lookup, including when game time is frozen. Cache hits
require no reflection; constructing a thread's first cache uses the optional
mod's public constructor.

## Verification

The opt-in test mod runs in a fresh dedicated-server world under
`build/speedee-compat-test/`. It is excluded from the distributed Skein jar.
Checks exercise actual transformed Minecraft and SpeeDeeEntity classes:

- Eight simultaneous threads grow independent maps to 16,384 entries each and
  check every value after rehashing.
- Same-thread reuse, dimension isolation, and forward/backward game-time changes.
- Empty sweeps followed by stone placement during the same game tick, queried
  through Skein workers; direct chunk placement/removal also invalidates.
- On indexed builds, collider addition/removal during the same tick, comparison
  with vanilla's entity-collision query, and an untouched shared collider list.
- 512 moving item entities, alternating 4 and 0.25 blocks/tick, for 50 cluster
  ticks and 50 grid ticks with eight workers. Assertions require both schedulers
  to run, every item to tick, no removed items, no worker failures, and no
  quarantined dimensions. Chunk loading finishes before measured frames begin.
- Startup without SpeeDeeEntity verifies that optional level mixins are absent.

Tested jars:

| Build | SHA-256 |
| --- | --- |
| Installed legacy 1.0.1 | `e6a1ef87ece2bfbfba7ffb0becec5fb5f66c27ae10f21a5a0d9c1ecc22b4dc47` |
| Local indexed 1.0.2 | `3abbeb8ec25764612c7a8e3064b5029f9f6448fcccf31872ca997a8f9be3c5d4` |

Run with an existing EULA agreement in `run/eula.txt`:

```sh
./gradlew build --offline
./gradlew runSpeedeeTest --offline -PspeedeeTestJar=run/mods/speedeeentity-1.0.1.jar
./gradlew runSpeedeeTest --offline -PspeedeeTestJar=/absolute/path/to/speedeeentity-1.0.2.jar
./gradlew runSpeedeeTest --offline -PspeedeeTestJar=none
```

Each run writes `result.txt` and `logs/latest.log` beneath its test directory.
Gradle fails if the test does not report success. These checks cover dedicated
servers on Minecraft 1.21.1 / NeoForge 21.1.219; they do not establish compatibility
with every other mod or change SpeeDeeEntity's own collision semantics.
