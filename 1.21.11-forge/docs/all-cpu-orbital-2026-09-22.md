# All-CPU setting and Orbital Strike Cannon test — 2026-09-22

`general.useAllCpuThreads` defaults to `false`. Enable it in
`config/skein-common.toml` and restart the server or reopen the world:

```toml
[general]
useAllCpuThreads = true
```

It overrides `threadCount`, removes the automatic 16-worker cap, and creates
`max(1, availableProcessors - 1)` workers. The server thread also executes tasks.
Adaptive scheduling remains available and workers park when there is no work,
so this setting permits additional CPU usage when useful. It does not guarantee
100% utilization or a speedup for workloads with limited parallelism.

## Verification

The dedicated-server fixture used Minecraft 1.21.1, NeoForge 21.1.219, Skein,
SpeeDeeEntity 1.0.2, and the exact supplied
`Orbital-Strike-Cannon-V7-mod.jar` in fresh worlds under
`build/orbital-compat-test/`.

- Java reported 28 logical processors. With `useAllCpuThreads = true` and a
  deliberately conflicting `threadCount = 8`, the real server created 27
  workers, confirming that the new setting takes precedence.
- The collision checks and 512 moving-entity stress test passed through both
  cluster and grid scheduling with the larger pool.
- Adaptive scheduling was enabled for the cannon test. Two simultaneous
  `orbital:stab_power_3` strikes spawned 404 TNT and damaged their target.
  Two simultaneous `orbital:nuke_power_1` strikes spawned another 1,262 TNT.
  All 1,666 TNT completed and were removed, with zero recorded Skein tick
  failures or quarantined dimensions. The test asserts that cannon entities
  actually pass through parallel scheduling.
- The standard build and core verification checks passed.

The supplied Orbital jar also logs **30 function parse failures and 12 recipe
parse failures** on Minecraft 1.21.1. Examples include newer `custom_name`
component syntax and the `max_command_forks` gamerule in
`orbital:gr_1_21_11`. These load-time errors are separate from the successful
direct strike tests; crafting, rod delivery and every cannon variant have not
been verified. The original jar was used unchanged.

Jar SHA-256 values:

| Jar | SHA-256 |
| --- | --- |
| Orbital-Strike-Cannon-V7-mod.jar | `5ff97de21c391ba958e03bdd241d0fb75bab1f9e0cc166119e70fc266676a0c1` |
| speedeeentity-neoforge-1.21.1-1.0.2.jar | `3abbeb8ec25764612c7a8e3064b5029f9f6448fcccf31872ca997a8f9be3c5d4` |

Reproduce with an existing EULA agreement in `run/eula.txt`:

```sh
./gradlew runSpeedeeTest --offline \
  -PspeedeeTestJar=/absolute/path/to/speedeeentity-neoforge-1.21.1-1.0.2.jar \
  -PorbitalTestJar=/absolute/path/to/Orbital-Strike-Cannon-V7-mod.jar
```

The opt-in test writes its result to `build/orbital-compat-test/result.txt` and
its complete log to `build/orbital-compat-test/logs/latest.log`. Its classes and
dependencies are excluded from the distributed Skein jar.
