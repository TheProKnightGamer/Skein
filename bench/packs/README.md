# Pack compatibility probe

Runs a real modpack on two dedicated servers, one without Skein and one with it.
Every block and entity the pack's mods add is placed in a force-loaded area, the
servers tick for a few minutes, and the error lines are compared. A failure that
only appears with Skein is Skein's to fix. Python 3 standard library only.

```bash
cd mod_dev/skein
W=bench/work/packs/<name>

# 1. Two servers from a CurseForge instance, on the bench harness's installed loader.
#    --drop leaves a jar out of both, --replace swaps one for another in both.
python3 -I bench/packs/packserver.py --instance "<instance dir>" --target 1.21.1-neoforge \
    --skein 1.21.1-neoforge/build/libs/skein-1.0.1-1.21.1-neoforge.jar --out $W

# 2. The probe: every modded block id (from blockstates) and entity id (from lang files),
#    as console commands (a datapack function would be rejected whole by one bad id).
#    The last argument is the data pack format: 48 for 1.21.1, 15 for 1.20.1.
python3 -I bench/packs/genprobe.py $W/plain/mods $W/probe 48

# 3. Boot the server without Skein, moving aside client-only mods and anything that
#    fails without Skein; give the Skein server the same list; boot it.
J=/usr/lib/jvm/java-21-openjdk-amd64/bin/java
A=libraries/net/neoforged/neoforge/21.1.252/unix_args.txt
python3 -I bench/packs/packboot.py $W/plain $J $A 20 --drop-failing
python3 -I bench/packs/packserver.py --instance x --target x --skein x --out $W --apply-removed $W/plain/removed.txt
python3 -I bench/packs/packboot.py $W/skein $J $A 20

# 4. Probe both (set adaptive = false in $W/skein/config/skein-common.toml first so the
#    in-level phases run on everything), then compare.
python3 -I bench/packs/runprobe.py $W/plain $W/probe $J $A 180
python3 -I bench/packs/runprobe.py $W/skein $W/probe $J $A 180
python3 -I bench/packs/compare.py $W/plain/probe.json $W/skein/probe.json
```

packboot reads both NeoForge's and Forge 1.20.1's crash reports to find the jar to move
aside. A client-only mod that breaks the server from a mixin, before any mod loads (Entity
Texture Features on 1.20.1), names no jar; move it aside by hand.

Servers run at nice 10 with a 6 GB heap, one at a time. Worlds are flat: world
generation is not what this tests, and modded generation in a big pack is slow.

Limits: before 1.21.5 a chunk is only random-ticked with a player within 128 blocks,
and these servers have none, so random ticks are not exercised. Ids that cannot be
placed or summoned on their own (multiblock parts, projectiles needing an owner)
simply fail their command. A race that corrupts state without throwing does not
show up as an error line.
