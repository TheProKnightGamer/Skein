# SuperChunk leaf-decay verification — 2026-09-22

The packaged SuperChunk 0.4.0 build passes the saved-leaf-update regression test, both alone and with Skein 1.0.0. The older installed SuperChunk 0.3.0 build reproduces the bug on an identical copy of the saved fixture.

| Scenario | Unsupported leaves | Supported leaves | Persistent leaves |
| --- | --- | --- | --- |
| 0.4.0, freshly created fixture | 8/8 updated to distance 7 and decayed | 8/8 retained | 8/8 retained |
| Old 0.3.0, reloaded fixture | 8/8 stuck at distance 1; none decayed | 8/8 retained | 8/8 retained |
| 0.4.0, same reloaded fixture | 8/8 updated to distance 7 and decayed | 8/8 retained | 8/8 retained |
| 0.4.0 + Skein, world saved by the broken build | 8/8 recovered and decayed | 8/8 retained | 8/8 retained |

## Method

- Minecraft 1.21.1 dedicated server, NeoForge 21.1.248, Java 21; disposable flat worlds, CPU world generation, four SuperChunk workers.
- Tested oak, birch, spruce, jungle, acacia, dark oak, mangrove and cherry leaves. Azalea variants were not included.
- Placed nonpersistent leaves next to matching logs. Removed the logs and checked distance propagation before checking actual disappearance through vanilla random ticks.
- For the reload fixture, logs were at X=15 and leaves at X=16, on opposite sides of a chunk boundary. Removed logs while game ticks were frozen, saved and stopped. Region-file NBT confirmed eight pending leaf updates on disk, each due in one tick.
- The old and fixed builds loaded separate copies of that same fixture. The final Skein run instead loaded the world saved by the old build after its updates had remained stuck.
- Checked that all four fixture chunks reached `ENTITY_TICKING` after 100 normally paced ticks. This matters because chunk loading is asynchronous: the initial test's 20-tick sprint completed before loading finished and gave an invalid early assertion.
- Used `randomTickSpeed=0` to inspect leaf distance, then `4096` for 40 ticks to verify actual decay without a long probabilistic wait. This tests correctness, not default decay timing or performance.
- A small test-only mod supplied NeoForge ticking tickets so vanilla random ticks ran without a connected player. It also reported chunk status and tick queues; it did not change leaf blocks, schedule leaf updates, or call leaf tick methods.
- Supported nonpersistent leaves and unsupported persistent leaves served as controls. All survived every scenario.

The old build saved all eight stranded leaf updates again. Both fixed reload scenarios ended with zero pending updates at the tested leaf positions. All four completed server runs exited normally with no ERROR/FATAL log entries. SuperChunk's supported Skein policy was active: dimension threading enabled; per-level parallel phases disabled.

No SuperChunk source or existing user worlds were modified. These results cover the controlled fixture, not an inspection of an existing gameplay save.

## Artifacts

- Machine-readable results
- Test driver
- Test-only ticket/status helper
- Fresh-world log
- Old-build reproduction log
- Fixed-build reload log
- Skein recovery log

Tested jar SHA-256 hashes (version labels alone do not identify the old build):

```text
Old SuperChunk 0.3.0: 9540842fa68af4331b73411e88fe0e7809552f483338978f48961e432a7f421d
SuperChunk 0.4.0:     2f57c421a3f5d36dd0c7861726cc231bf333252275bf408077060be158aab624
Skein 1.0.0:          94b8147ff72e0f6b025b342354d880f1b9e9886bb99edc0d2a2efa9323fc07c6
```
