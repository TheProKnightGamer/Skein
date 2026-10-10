# Running real modpacks -- 2026-10-07

Everything before this round was tested against vanilla plus a handful of mods.
This round ran two large CurseForge packs as dedicated servers, with and without
Skein, and fixed what broke. The same changes are in all eight lines.

## How

`bench/packs/` turns a CurseForge instance into two servers (one with Skein), boots
them with the same mods, places every modded block (from blockstates) and summons
every modded entity (from lang files) in a force-loaded flat area, lets it tick for
three minutes, asks `/skein status`, and compares the error lines. The in-level
phases were forced on (`adaptive = false`) so they ran on everything.

- **Create Chronicles: The Endventure** -- 1.21.1 NeoForge, 264 mods, Create 6.0.10
  and about 25 add-ons, Neruina, Cupboard, Create Big Cannons, Iron's Spellbooks,
  Lithium, nine dimensions. 17,246 blocks placed, 908 entities.
- **Season-2_75** -- 1.20.1 Forge, 230 mods, Create 6.0.6, Twilight Forest, the
  Aether, Dimensional Doors, Ice and Fire, Cupboard, Cerbon's API.

Create itself was also read, decompiled, for state its block entities share
beyond a cell (a separate audit; findings below).

## What broke, and what Skein does now

1. **Cupboard held back every entity Skein's workers added.** Cupboard queues an
   entity added from a thread without "server" in its name until the next add on
   one that has it. Under Skein that might never come: item drops and spawns sat
   in its queue. The workers are now `Skein Server Worker #N`. A new in-game check
   (`ThreadIdentityVerification`) confirms each worker is named as a server thread
   and that Forge's and NeoForge's `EffectiveSide` answer `SERVER` on it.

2. **Neruina knocked two dimensions out of parallel ticking.** It keeps one
   `TickHandler` for the server and ticks it from every level; two dimensions at
   once raced on its list of recent errors (`NoSuchElementException`). Its
   bookkeeping now holds a lock (`NeruinaTickHandlerMixin`, only when Neruina is
   installed). Neruina also wraps the same three calls Skein guards -- entity,
   block entity and random ticks -- and at the same priority Skein's guards were
   outside its, so a refusal (Skein turning down a chunk load on a worker) would
   have reached Neruina as a crash and suspended the entity. Skein's guards are
   now at priority 100, nearest the tick: a refusal ends that one tick, a failure
   inside a parallel pass demotes the type (`Demotions.absorb`), and only a type's
   failures on the level thread reach Neruina, as without Skein.

3. **Create Big Cannons hung the server.** Ritchie's Projectile Library fills a
   per-level fastutil set of chunks to force-load from every shell's tick. Two
   shells in different cells corrupted it and the next `add` probed it forever: a
   worker that never came back, and a server stuck at the barrier. The set now
   holds a lock (`RplChunkManagerMixin`).

4. **Skein crashed Season-2_75 at start.** Its block scan asks each block class
   which class overrides `entityInside`; Java's reflection resolves every type in
   the class's method signatures, and a block of Cerbon's API names a client-only
   particle class. The `NoClassDefFoundError` was not caught and the server died.
   Such a class is now treated as one that reacts to entities (the safe answer),
   asked about once, and explained in the log.

5. **Iron's Spellbooks threw `ConcurrentModificationException`.** It keeps the
   entities a spell just hit, to skip their knockback, in one static `HashMap`
   written from spell entities' ticks. Both sides now hold a lock
   (`IronsKnockbackMixin`).

6. **Create** (from the audit): a kinetic network's members and stress totals,
   the rotation walks over them, brass tunnels' static scratch maps, redstone-link
   frequencies, deployers' captured drops, and Create 6's logistics network and
   summary caches are shared beyond a cell. Each is entered through a few methods,
   which now hold one lock (`CreateCompat.LOCK`); they run when something changes,
   not every tick. Display links, redstone links and the 6.0 logistics blocks reach
   past any margin and tick on the level thread whenever installed
   (`CreateCompat.LONG_REACH`). Checked against Create 0.5.1, 6.0.6 and 6.0.10: the
   classes and methods are the same, except that 0.5.1 has no logistics network or
   timed caches, and those two are only applied where they exist. `KineticNetwork`
   and `RedstoneLinkNetworkHandler.Frequency` are named as return types through
   compile-only stubs (`src/compatStubs`), which are not packaged.

7. **Large entities.** An entity was filed by its position; a Create contraption's
   drills, harvesters and deployers act anywhere in its box. Entities are now filed
   by the larger of their follow range and their own box plus three blocks, so a
   contraption, ship or other moving structure wider than the margin keeps to one
   cell or ticks on the level thread. A mob is unaffected.

8. **Shared vanilla and loader state** that mods reach from their ticks now holds a
   lock: saved data (`DimensionDataStorage`, `SavedDataStorage` on 26.2), the player
   list's stats and advancements maps (every fake player looks itself up there),
   the fake-player factory (NeoForge, and Forge 1.20.1; Forge 1.21+ has none), and
   NeoForge's capability-cache listeners.

9. **Hangs are remembered.** When a parallel phase has been stuck for ten seconds,
   Skein samples what each stuck thread is ticking twice and demotes that type.
   Every demotion, thrown or hung, is written to `config/skein-demoted.txt` and
   read back at the next start; `/skein unquarantine` empties it. The stuck thread
   itself cannot be stopped, so the watchdog still ends that run, but the type does
   not get to do it again.

10. **Canary** (1.20.1) reads neither of Lithium's `mods.toml` tables but a
    `canary.overrides.properties` at the root of each mod jar; Skein ships one. With
    Canary 0.3.3 at its defaults: "9 override(s) found", each "added by mods
    [skein]", and Skein reports both running.

Not Skein's, seen without Skein too: Iron's Spellbooks' chain lightning throws when
summoned with no caster (Neruina catches it either way; under Skein the type is
demoted first); Neruina's Forge 1.20.1 build does not start on NeoForge 1.20.1
(47.1); several client-only mods do not start a dedicated server.

## Results

Create Chronicles, final jar: alive, no hang, no quarantined dimension, no Cupboard
warnings; 243 hooks, 0 missing, 2 left to other mods (explosion rays to amendments,
Create Big Cannons and Sable; entity tracking to Sable), all four phases running.
950 error lines against 948 without Skein; the extra two are chain lightning
(above), whose type was demoted after its first failure.

Season-2_75, final jar: starts (it did not before), alive; 216 hooks, 0 missing, all
four phases; the same placements and summons as without Skein; no Skein warnings.
The only lines not in the plain run are the class loader's errors for the two
Bosses of Mass Destruction blocks from item 4, each followed by Skein's line saying
where they come from.

Each final jar, on its dedicated server (`bench/leg.py`, `mixed`: 1440 penned
animals, 576 hoppers in rings, a random-tick farm, 480 animals in the nether, nine
connected players):

| Line | Hooks | Entities | Items | Errors | Phases |
| --- | --- | --- | --- | --- | --- |
| 1.20.1 Forge | 168, 0 missing | unchanged | 9216/9216 | 0 | all four |
| 1.20.1 NeoForge | 168, 0 missing | unchanged | 9216/9216 | 0 | all four |
| 1.21.1 Forge | 172, 0 missing | unchanged | 9216/9216 | 0 | all four |
| 1.21.1 NeoForge | 182, 0 missing | unchanged | 9216/9216 | 0 | all four |
| 1.21.11 Forge | 172, 0 missing | unchanged | 9216/9216 | 0 | all four |
| 1.21.11 NeoForge | 182, 0 missing | unchanged | 9216/9216 | 0 | all four |
| 26.2 Forge | 173, 0 missing | unchanged | 9216/9216 | 0 | all four |
| 26.2 NeoForge | 183, 0 missing | unchanged | 9216/9216 | 0 | all four |

From 1.20.3 the items are now counted with the game frozen (`/tick freeze`); an
earlier 9217 on 1.21.11 Forge was the count racing the hoppers, and frozen it is
exact.

Each final jar also started beside the mods its version has
(`bench/bootcheck.py`): Canary, Neruina, Ritchie's Projectile Library and Cupboard
on 1.20.1 Forge (180 hooks); Canary, the library and Cupboard on 1.20.1 NeoForge
(171); Neruina with Configurable, the library and Cupboard on 1.21.1 NeoForge
(193); Neruina with Configurable on 1.21.11 and 26.2 NeoForge (191, 192). Every
hook applied, all four phases running, no errors.

The in-game suites pass on the NeoForge 1.21.1, 1.21.11 and 26.2 lines, with the
new thread-identity check, and the demotion test now also checks that the three
demoted types are written to `config/skein-demoted.txt`, restored by a fresh load,
and cleared with the file by `/skein unquarantine`. Core tests pass on all eight.

## Limits

A race that corrupts state without throwing or hanging is still not caught. The
probe places things one at a time with no players, so random ticks are not
exercised (no player within 128 blocks before 1.21.5) and networks spanning cells
are not built; the Create fixes come from reading its code and are verified to load
and apply, not under a large running factory. Two packs are two packs.
