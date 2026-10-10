# Skein cross-version benchmark

This is the yardstick for "every port of Skein is the same speed or better". For one target (a Minecraft
version plus a loader), it measures a real dedicated server twice: **A** is the loader with nothing in
`mods/`, and **B** adds the Skein jar under test. Both run the same freshly generated world and the same
scenario. Every version is judged by what Skein does for that version's own vanilla. Absolute ms/tick is
never compared across versions, because vanilla itself changed between them.

Python 3 standard library only. By TheProKnightGamer.

## Usage

```bash
cd mod_dev/skein

# install a dedicated server for a target (official installer, cached under bench/servers/)
python3 bench/run.py --provision-only --target 1.20.1-forge

# the full matrix: 5 scenarios x (A, B) x 3 repetitions = 30 legs, ~3-4 min each plus lock waits
python3 bench/run.py --target 1.20.1-forge \
    --jar 1.20.1-forge/build/libs/skein-1.0.1-1.20.1-forge.jar --scenarios all

# a subset, or fewer repetitions while iterating
python3 bench/run.py --target 26.2-neoforge --jar <jar> --scenarios entities,quiet --reps 1

# acceptance check against the 1.21.1-neoforge baseline (exit 0 = accepted)
python3 bench/compare.py bench/results/1.21.1-neoforge-<id>.json bench/results/26.2-neoforge-<id>.json

# one leg by hand (run.py does exactly this, at nice 10)
nice -n 10 python3 bench/leg.py --target 1.21.1-neoforge --scenario entities \
    --config B --jar <jar> --out leg.json [--warmup 10 --samples 3] [--jfr leg.jfr]

# two Skein builds against each other: interleaved legs, median of per-pair ratios
python3 bench/ab.py --target 1.21.1-neoforge --old <old.jar> --new <new.jar> \
    --scenarios entities,mixed,quiet --reps 3 --max-load 12 --out bench/work/ab-<name>

# where a leg's time went (from leg.py --jfr): top methods per thread group
python3 bench/jfrsum.py leg.jfr --java <jdk>/bin/java [--match skein]

# a TNT cannon's payload build (Orbital Skybender save), real time or stepped for an exact end state
python3 bench/osc.py --world "<save folder>" --n 50 --jar <jar> --seconds 560 --out bench/work/osc/run
python3 bench/osc.py --world "<save folder>" --n 20 --jar <jar> --step 1997 --dump-tnt --out bench/work/osc/det

# the same with another mod installed in both configs (repeatable), e.g. SpeeDeeEntity
python3 bench/leg.py --target 1.20.1-forge --scenario mixed --config B --jar <jar> \
    --extra-mod <speedeeentity.jar> --out leg.json

# a mod a vanilla client would need (Canary, Neruina, ...) keeps the bots out; check that Skein
# starts beside it, which hooks applied and what the log says, with no players
python3 bench/bootcheck.py --target 1.20.1-forge --jar <jar> --extra-mod <canary.jar> --out check.json
```

`run.py` takes hours. Start it with `run_in_background` (or `nohup`) and read `bench/results/<run-id>.md`
when it is done. If a run is interrupted, rerun it with the same `--run-id` (the file name in
`bench/work/`). Legs that are already complete are kept, so it resumes where it stopped.

## Acceptance rule (`compare.py`)

- For every scenario, the port's speedup (vanilla ms / Skein ms, on its own version) must be at least
  **0.95x** the 1.21.1-neoforge speedup.
- On `quiet`, Skein's ms/tick must be at most **1.03x** vanilla's. Skein must not cost a server that has
  nothing to parallelise.
- Every leg's correctness checks must pass, and `/skein status` must report 0 missing hooks.

## Targets

Everything version- or loader-specific lives in `targets.py`: packet ids, command dialect, TPS command
and JVM.

| target | loader build | Java | tick-time command | protocol | item NBT in a container | gamerule ids |
| --- | --- | --- | --- | --- | --- | --- |
| 1.20.1-forge | Forge 47.4.23 | 17 | `/forge tps` | 763, no configuration phase | `Count:16b` | camelCase (`randomTickSpeed`) |
| 1.20.1-neoforge | NeoForge (Forge fork) 47.1.106 | 17 | `/forge tps` | 763 | `Count:16b` | camelCase |
| 1.21.1-forge | Forge 52.1.16 | 21 | `/forge tps` | 767 | `count:16` | camelCase |
| 1.21.1-neoforge | NeoForge 21.1.252 | 21 | `/neoforge tps` | 767 | `count:16` | camelCase |
| 1.21.11-forge | Forge 61.2.1 | 21 | `/forge tps` | 774 | `count:16` | snake_case (`random_tick_speed`) |
| 1.21.11-neoforge | NeoForge 21.11.45 | 21 | `/neoforge tps` | 774 | `count:16` | snake_case |
| 26.2-forge | Forge 65.1.3 | 25 | `/forge tps` | 776 | `count:16` | snake_case |
| 26.2-neoforge | NeoForge 26.2.0.88 | 25 | `/neoforge tps` | 776 | `count:16` | snake_case |

The snake_case rename (1.21.11) also changed some rules' meaning. `doFireTick false` became
`fire_spread_radius_around_player 0`, and `commandModificationBlockLimit` became
`max_block_modifications`. Both output formats are parsed:
`Overall: Mean tick time: X ms. Mean TPS: Y` (Forge) and `Overall: Y TPS (X ms/tick)` (NeoForge). Each
is a mean over the last 100 ticks.

Protocol differences the bots handle: 1.20.1 has no configuration phase, and its login hello carries a
`hasUuid` flag. 1.20.5+ answers the known-packs query. 1.21.2+ puts the teleport id first in
PlayerPosition, adds `particleStatus` to ClientInformation, and needs a `player_loaded` packet. Forge's
login-query handshake is answered "not understood", as a vanilla client answers it.

## Scenarios (`scenarios.py`)

Every leg starts from a deleted world. The world is superflat with explicit layers (bedrock y=-64,
dirt y=-63..-62, grass y=-61) and seed `skein-bench`, and every scenario is built over RCON from vanilla
commands in a fixed order. Nine headless players (`mcbot.py`) stand in creative mode at y=0 on a 3x3
grid. They are real: before 1.21.5 a chunk is only random-ticked while a non-spectator player is within
128 blocks, and 1.21.2+ pauses an empty server. The grid puts every force-loaded chunk within 128 blocks
of a player, so every version ticks the same chunks.

| scenario | what | correctness check |
| --- | --- | --- |
| `entities` | 1440 animals (4 cow, 3 sheep, 2 pig, 1 villager per pen) in 144 two-high glass pens over 24x24 chunks (32x32 force-loaded) | tagged entity count unchanged |
| `hoppers` | 576 hoppers as 144 closed 4-hopper rings, 16 items each (9216 items circulating), over 24x24 chunks (32x32 force-loaded) | item total conserved exactly |
| `random` | `randomTickSpeed 80` over 32x32 chunks (40x40 force-loaded) of moist farmland, water every 9 blocks. Steady state: each tick scans 9x2x9 for water and nothing ever changes | none needed |
| `mixed` | all three (farm 32x32, pens and rings 24x24, 40x40 force-loaded), plus 480 animals on a walled platform in the nether (8x8 chunks, 16x16 force-loaded), so two levels are busy | both entity counts, item total |
| `quiet` | the regression guard: no force-loading, `randomTickSpeed 3`, and around each player 10 animals, 3 hopper rings and a 16x16 farm | counts and items |

Every busy area has a ring of at least 4 loaded, empty chunks around it, as a farm inside a player's
view would. Without the ring, the outer 3 chunks fall inside Skein's `entities.loadedMarginChunks` and are
pinned serial. That was measured at 346 of 1476 entities: an artefact of force-loading, not of the
workload.

Gamerules for every scenario: mob spawning, daylight, weather, phantoms, patrols, traders, wardens and
fire spread are off, and mob griefing is off, so nothing tramples the farmland. Time is fixed at noon.
Difficulty is easy.

## Method

- **One leg** (`leg.py`) is one server start: reset the world, config and mods, start the server,
  log in the 9 players, build the scenario, warm up 60 s, then take 12 samples of the loader's mean
  tick time 5 s apart. Then it checks correctness, captures `/skein status` (for B), and stops the server
  cleanly. It falls back to SIGKILL of its own process group only after 120 s. A leg takes about
  3-4 minutes and has a hard 14-minute cap.
- **Isolation**: legs run at nice 10, the same for A and B. If a `box-run` lock wrapper is on `PATH`,
  each leg holds its heavy lock so nothing else heavy runs during a measurement. `run.py` also waits for
  at least 12 GB of `MemAvailable` before each leg, and `ab.py --max-load` waits for a quiet machine.
- **Interleaving**: for each repetition and each scenario, A then B. Steady background load on the
  machine is cancelled out by the A/B interleaving; bursty load is not, so compare pairs, not single legs.
- **Skein config (B)**: `config/skein-common.toml` has every key at its default except
  `[general] threadCount = 8`, so results do not depend on how many cores the box has free.
- **JVM**: the installer's own launch line (from `run.sh`) with `-Xms2G -Xmx4G -XX:+UseG1GC
  -XX:MaxGCPauseMillis=100`. Server on 127.0.0.1:25711, RCON on 25811.
- **Recorded per leg**: every sample (overall and per dimension), the median, load averages before
  and after, `MemAvailable`, every other JVM on the box (pid, RSS, what it is), log error and
  `Can't keep up` counts, `/skein status`, and the SHA-256 of the jar.
- **Reported**: per scenario, the median of the per-leg medians for A and for B, speedup = A/B, and the
  spread (max-min over median) of the per-leg medians.

## Files

```
bench/targets.py      the per-target table (protocol ids, dialect, TPS command, Java, installer)
bench/mcbot.py        silent headless client for 1.20.1..26.2
bench/rcon.py         RCON client (one request at a time; Minecraft's reader drops pipelined packets)
bench/scenarios.py    scenario builders and checks
bench/leg.py          one measurement leg
bench/run.py          provisioning + the interleaved matrix + summary
bench/compare.py      acceptance check against the baseline
bench/results/        summaries (.json + .md), the only output meant to be shared
bench/servers/  bench/cache/  bench/work/     installed servers, installers, per-leg scratch (not shared)
```

## Caveats

- **1.20.1 keeps spawn chunks loaded** (radius 11 around 0,0, always ticking). 1.21.x defaults
  `spawnChunkRadius` to 2. This is identical for A and B within a version. It is one more reason not to
  compare absolute numbers across versions.
- **Forge 1.21+ and 26.2 targets** accept the bots only if Forge lets a vanilla client join a server
  whose mods don't require client-side presence. Skein registers no network channel. If a Forge target
  refuses the bots, the leg fails at "bot Bench1 failed to join" and prints the server's reason.
- The per-dimension numbers come from the loader's own per-level timers. With dimension threading on,
  those timers measure a level's tick on its worker, so for B they are CPU-time-like and don't sum to
  the overall figure. Only the overall figure is used for acceptance.

## Verified live

The bots, every scenario's command dialect and the TPS parser were checked against real servers on
2026-09-30: 1.20.1-forge, 1.21.1-neoforge, 1.21.11-neoforge and 26.2-neoforge, all 9 players online and
every correctness check passing. Another session ran the harness on 1.21.1-forge. The remaining Forge
targets share Forge's code paths: the `/forge tps` format, the login-query handshake, and the
empty-reply RCON behaviour handled below.

## Traps this harness already handles

- **A teleport confirm alone does not move a player's chunk tickets.** `ChunkMap.move` (player
  tickets, simulation distance, the natural-spawn counter that gates random ticks before 1.21.5) runs
  on the client's next movement packet. A vanilla client always sends one right after confirming a
  teleport. A bot that only confirms keeps all of that at its login spot, however far `/tp` moved it.
  The bots answer every PlayerPosition with a confirm and then a MovePlayer.Pos.
- **1.20.1 has no configuration phase.** The server's decoder stays on the 3-packet LOGIN table until
  it sends its first PLAY packet, so a ClientInformation sent on login-success is decoded as LOGIN id 8
  (`Index 8 out of bounds for length 3`) and the player is kicked. The bots wait for the first PLAY
  packet.
- **Minecraft's RCON reader needs exactly one packet per socket read**, and **Forge sends no reply at
  all to a command with empty output**. See `rcon.py`.
- **1.21.2+ pauses an empty server** (`pause-when-empty-seconds`, default 60). Every leg writes `0`,
  and a leg fails if the log ever says "Server empty for".
- **26.2 changed the count output** to `Test passed. Count: N`. The count parse ignores case.
- **The hopper rings keep passing items while they are counted.** Reading 576 hoppers takes many
  commands, so an item in transit could be counted twice or not at all. From 1.20.3 (`/tick freeze`)
  the count is taken with the game frozen; on 1.20.1 it can still be one off either way.
- **Water under a wall drowns pigs.** A pen wall over a farm channel leaves a one-block water tunnel
  a pig fits into, so the walls replace the ground block too.
- **Forge's maven returns 403 to Python's default User-Agent.** The installer download sends a
  curl-style one.
- `run.py` measures a frozen copy of the jar (in `work/<run-id>/`), so a rebuild during a long run
  cannot mix two builds into one result. Background jobs from the Bash tool are killed after 30
  minutes, so start `run.py` with `setsid nohup ... &`.
