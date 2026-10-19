# Skein 1.1.0: one feature set in all eight lines -- 2026-10-17

Before building 1.1.0, every line was compared with every other: which classes exist where, which
methods each class declares, and which vanilla method each hook targets. The original two lines,
1.21.1 NeoForge and 1.21.11 NeoForge, were developed side by side, and the other six were ported
from them, so a gap would show as a method or a hook one line has and its sibling lacks.

## What was missing, and is now in every line

- **The 2026-10-09 round** ([optimization-2026-10-09.md](optimization-2026-10-09.md)), which had
  only ever landed in 1.21.1 NeoForge: the governor re-timing a stale path properly, the wide grid
  for members too wide for a cell (villagers), cheaper entity filing, the POI lock skipping
  re-entrant acquires, the `getChunk` wrapper no longer letting its `Operation` escape, and the
  busiest level ticking on the server thread. Merged into each line three ways against the
  1.21.1 NeoForge commits, with each line's own API names; the core tests that came with it (wide
  grid geometry, governor re-timing) run in every line.
- **The Create belt fix**: a block that writes from its landing hook is parked for clustered
  entities like `entityInside`. In 1.20.1, 1.21.1 and 1.21.11 (where the hook is called
  `updateEntityMovementAfterFallOn`); 26.2 has no such hook.
- **SpeeDeeEntity 1.0.2's move memos** read the level's tick cache directly; every line now hands
  each thread its own. SpeeDeeEntity's internals are the same in every version it ships for.
- **SuperChunk's mid-tick chunk tasks** wait while the server thread is in a cell, in 1.20.1 and
  1.21.11 NeoForge as in 1.21.1 NeoForge -- the three versions SuperChunk exists for.
- **Alternate Current's per-thread wire handlers**, in 1.21.11 and 26.2 NeoForge as in 1.21.1:
  Alternate Current 1.9.0 has the same handler in all three. Its in-game test runs in those lines.

## What differs by design

- **Mods that do not exist for a version.** Create is on 1.20.1 and 1.21.1 NeoForge only;
  Alternate Current has no Forge builds after 1.20.1, and its 1.20.1 build (1.7.0) keeps an older
  handler with a different constructor that Skein does not cover yet; Forge after 1.20.1 has no
  `FakePlayerFactory` and no `EffectParticleModificationEvent`; only NeoForge has capability
  listeners, `Block.capturedDrops` and `SectionStorage.remove` (whose point-of-interest lock is
  therefore in the NeoForge lines from 1.21.1 on).
- **Vanilla's own changes.** Explosions, portals, teleports, chunk tickets, entity tracking and the
  random-tick loop were rewritten in 1.21.2, the world clock in 26.2, and 1.20.1 is on SRG names
  with an older mixin library; each line hooks the methods its version has, for the same effect.
- **Order of block entities** (`blockEntities.vanillaOrderLimit`) and the scheduled-tick phase are
  the same in every line.

## How it was checked

Every line builds and passes `coreTest`. The in-game suites (26.2, 1.21.11 and 1.21.1 NeoForge have a
task for them) ran on 26.2 and 1.21.11 NeoForge alone, with SpeeDeeEntity 1.0.2 and with Alternate
Current 1.9.0, and on 1.21.1 NeoForge alone, whose code this round did not touch. All passed;
the Alternate Current test matched vanilla's wire power and lamp state in 100 circuits on both new
lines.

Each jar was then booted on its own dedicated server, given 30 seconds, and asked for `skein status`:

| Line | Booted with | Hooks | Phases |
|---|---|---|---|
| 26.2 NeoForge | SpeeDeeEntity, Alternate Current | 200, 0 missing | all five |
| 26.2 Forge | SpeeDeeEntity | 189, 0 missing | all five |
| 1.21.11 NeoForge | SpeeDeeEntity, Alternate Current | 200, 0 missing | all five |
| 1.21.11 Forge | SpeeDeeEntity | 189, 0 missing | all five |
| 1.21.1 NeoForge | SpeeDeeEntity, Alternate Current, SuperChunk | 201, 0 missing | all five |
| 1.21.1 Forge | SpeeDeeEntity | 189, 0 missing | all five |
| 1.20.1 NeoForge | SpeeDeeEntity | 185, 0 missing | all five |
| 1.20.1 Forge | SpeeDeeEntity | 185, 0 missing | all five |

The first round of boots caught two mistakes in the merge, both fixed before these runs: the
`SectionStorage.remove` lock had been carried into the Forge lines, where the method does not exist
(an unapplied hook switches off every in-level feature), and the 1.20.1 landing-hook lookup used
the mapped name where 1.20.1 needs the SRG one.

Not covered by a boot: **SuperChunk on 1.20.1 and 1.21.11.** The local builds of SuperChunk for
those versions do not start a dedicated server even without Skein (exp4j and the LWJGL module are
not on the server's classpath), so the mid-tick hook there is checked by compiling only. On 1.21.1
it boots and applies.

The boots also turned up two packaging faults in SpeeDeeEntity 1.0.2, since fixed in its builds
and re-checked with every line above (all eight start with no SpeeDeeEntity errors, and its suite
passes again on the three NeoForge lines that have one):

- On Forge 1.21.1 (and 1.20.1) the jar with the plain name was only the jar-in-jar task's input.
  On 1.21.1 it named `META-INF/jarjar/mixinextras-forge-0.5.3.jar` in its metadata without
  containing it, so Forge stopped with a `NullPointerException` in `JarInJarDependencyLocator`;
  on 1.20.1 it lacked MixinExtras and loaded only beside a mod that brings it, such as Skein.
  The jar-in-jar output now takes the plain name and the input is `-slim`.
- On Forge 1.21.11 its `pack.mcmeta` declared format 75 (the resource-pack number), which the
  1.21.11 server rejects as a data pack. It now declares 94.1, as Forge's own does.
