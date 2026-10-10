#!/usr/bin/env python3
"""Build one Orbital Skybender payload on a copy of a world, in real time, and record the tick times.

    python3 bench/osc.py --world "<save folder>" --n 20 --jar <skein.jar> --seconds 240 --out bench/work/osc/run1

The cannon's TNT dupers drop primed TNT into frozen stacks of thousands, and every piston push in the
builder drags whole stacks along; that is where a cannon world spends its tick. This boots the 26.2
NeoForge bench server on a copy of the save (never the save itself), with the Skein jar or (without
--jar) none. It kick-starts the cannon's two portal chunk loaders and enters payload size N on the
loader rails the way redstone-lab's scenarios/osc_build.py does, with the clock frozen so the input
pulses land on exact ticks. Then it lets the server run at full speed for --seconds, sampling
`/tick query` (mean and percentiles over the last 100 ticks) every few seconds and the primed-TNT count
now and then, optionally with a Flight Recorder profile of that window.

The world's own difficulty and game rules are kept. View and simulation distance are 4, as in the lab,
and there are no players: what ticks is what the chunk loaders keep loaded, as on the cannon's own server.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path

BENCH = Path(__file__).resolve().parent
sys.path.insert(0, str(BENCH))

from leg import launch_argv, port_busy  # noqa: E402
from rcon import Rcon  # noqa: E402
from targets import TARGETS, java_for  # noqa: E402

PORT, RCON_PORT, PASSWORD = 25711, 25811, "skeinbench"
LOADERS = [(54, 50), (51, 53)]
START, DATA = (863, 171, 826), (863, 168, 821)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--world", required=True, type=Path)
    ap.add_argument("--target", default="26.2-neoforge", choices=sorted(TARGETS))
    ap.add_argument("--n", type=int, default=20, help="payload counter value (1-255)")
    ap.add_argument("--jar", type=Path, help="Skein jar; omit for the loader alone")
    ap.add_argument("--extra-mod", action="append", default=[], type=Path)
    ap.add_argument("--config", type=Path, help="a skein-common.toml to use instead of threadCount = 8")
    ap.add_argument("--seconds", type=int, default=240)
    ap.add_argument("--interval", type=float, default=3.0)
    ap.add_argument("--jfr", type=Path)
    ap.add_argument("--heap", default="6G")
    ap.add_argument("--step", type=int, help="instead of running in real time, step exactly this many ticks "
                    "(deterministic: compare end states between builds)")
    ap.add_argument("--dump-tnt", action="store_true", help="record every primed TNT's position and fuse at the end")
    ap.add_argument("--out", required=True, type=Path)
    args = ap.parse_args()

    t = TARGETS[args.target]
    srv = BENCH / "servers" / t.name
    for p in (PORT, RCON_PORT):
        if port_busy(p):
            print(f"port {p} is in use; refusing to start")
            return 3
    args.out.mkdir(parents=True, exist_ok=True)

    # A fresh copy of the save, and only the mods asked for.
    shutil.rmtree(srv / "world", ignore_errors=True)
    shutil.copytree(args.world, srv / "world", ignore=shutil.ignore_patterns("session.lock"))
    mods = srv / "mods"
    mods.mkdir(exist_ok=True)
    for old in mods.glob("*.jar"):
        old.unlink()
    for jar in ([args.jar] if args.jar else []) + args.extra_mod:
        shutil.copy2(jar, mods / jar.name)
    (srv / "config").mkdir(exist_ok=True)
    (srv / "config" / "skein-demoted.txt").unlink(missing_ok=True)
    cfg = args.config.read_text() if args.config else "[general]\n\tthreadCount = 8\n"
    (srv / "config" / "skein-common.toml").write_text(cfg)
    props = {
        "online-mode": "false", "enable-rcon": "true", "rcon.port": RCON_PORT, "rcon.password": PASSWORD,
        "server-port": PORT, "server-ip": "127.0.0.1", "level-name": "world", "view-distance": 4,
        "simulation-distance": 4, "max-tick-time": -1, "spawn-protection": 0, "pause-when-empty-seconds": 0,
        "difficulty": "hard", "spawn-monsters": "true", "sync-chunk-writes": "true", "motd": "skein-osc",
    }
    (srv / "server.properties").write_text("".join(f"{k}={v}\n" for k, v in props.items()))
    (srv / "eula.txt").write_text("eula=true\n")
    for d in ("logs", "crash-reports"):
        shutil.rmtree(srv / d, ignore_errors=True)

    argv = launch_argv(srv, java_for(t.java))
    argv = [a for a in argv if not a.startswith(("-Xms", "-Xmx"))]
    argv.insert(1, f"-Xmx{args.heap}")
    argv.insert(1, f"-Xms{args.heap}")
    console = open(srv / "bench-console.log", "w")
    proc = subprocess.Popen(["nice", "-n", "10"] + argv, cwd=srv, stdin=subprocess.PIPE, stdout=console,
                            stderr=subprocess.STDOUT, start_new_session=True)
    result = {"world": str(args.world.name), "n": args.n, "jar": args.jar.name if args.jar else None,
              "extra_mods": [e.name for e in args.extra_mod], "samples": [], "tnt": []}
    rcon = None
    try:
        deadline = time.monotonic() + 300
        while "Done (" not in (srv / "bench-console.log").read_text(errors="replace"):
            if proc.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError("server did not start")
            time.sleep(0.5)
        rcon = Rcon("127.0.0.1", RCON_PORT, PASSWORD).connect(time.monotonic() + 60)
        cmd = rcon.command
        cmd("tick freeze")

        def step(n: int) -> None:
            cmd(f"tick step {n}")
            time.sleep(0.05 * n + 0.15)
            for _ in range(400):
                if "frozen" in cmd("tick query").lower():
                    return
                time.sleep(0.05)

        def setb(pos, state) -> None:
            cmd("setblock %d %d %d minecraft:%s" % (*pos, state))

        def tnt_count() -> int:
            m = re.search(r"count: (\d+)", cmd("execute if entity @e[type=minecraft:tnt]"), re.I)
            return int(m.group(1)) if m else 0

        for cx, cz in LOADERS:
            cmd(f"forceload add {cx * 16} {cz * 16}")
        step(40)
        for cx, cz in LOADERS:
            cmd(f"forceload remove {cx * 16} {cz * 16}")
        step(60)
        acts = {0: [(START, "redstone_block")]}
        for k in range(8):
            if args.n >> k & 1:
                acts.setdefault(12 + 4 * k, []).append((DATA, "redstone_block"))
                acts.setdefault(14 + 4 * k, []).append((DATA, "air"))
        for tick in range(max(acts) + 1):
            for pos, state in acts.get(tick, []):
                setb(pos, state)
            step(1)
        result["tnt_at_start"] = tnt_count()

        jcmd = str(Path(java_for(t.java)).with_name("jcmd"))
        if args.jfr:
            subprocess.run([jcmd, str(proc.pid), "JFR.start", "name=osc", "settings=profile",
                            f"filename={args.jfr.resolve()}"], check=True, capture_output=True, timeout=60)
        if args.step:
            t0 = time.monotonic()
            left = args.step
            while left > 0:
                chunk = min(left, 100)
                step(chunk)
                left -= chunk
            result["stepped"] = args.step
            result["step_seconds"] = round(time.monotonic() - t0, 1)
        else:
            cmd("tick unfreeze")
        t0 = time.monotonic()
        next_tnt = 0.0
        while not args.step and time.monotonic() - t0 < args.seconds:
            time.sleep(args.interval)
            q = cmd("tick query")
            elapsed = round(time.monotonic() - t0, 1)
            sample = {"t": elapsed}
            for key, pat in (("mean", r"Average time per tick: ([\d.]+)"), ("p50", r"P50: ([\d.]+)"),
                             ("p95", r"P95: ([\d.]+)"), ("p99", r"P99: ([\d.]+)")):
                m = re.search(pat, q)
                if m:
                    sample[key] = float(m.group(1))
            result["samples"].append(sample)
            if elapsed >= next_tnt:
                result["tnt"].append([elapsed, tnt_count()])
                next_tnt = elapsed + 20
            print(json.dumps(sample), flush=True)
        if args.jfr:
            subprocess.run([jcmd, str(proc.pid), "JFR.stop", "name=osc"], check=True, capture_output=True,
                           timeout=180)
        result["tnt"].append([round(time.monotonic() - t0, 1), tnt_count()])
        if args.dump_tnt:
            cmd("tick freeze")
            time.sleep(0.5)
            num = r"(-?[\d.]+(?:E-?\d+)?)d"
            pos = re.findall(rf"\[{num}, {num}, {num}\]", cmd("execute as @e[type=minecraft:tnt] run data get entity @s Pos"))
            fuse = re.findall(r"data: (-?\d+)s", cmd("execute as @e[type=minecraft:tnt] run data get entity @s fuse"))
            stacks = {}
            for i in range(min(len(pos), len(fuse))):
                key = f"{float(pos[i][0]):.4f},{float(pos[i][1]):.4f},{float(pos[i][2]):.4f} fuse={fuse[i]}"
                stacks[key] = stacks.get(key, 0) + 1
            result["tnt_state"] = dict(sorted(stacks.items()))
            result["tnt_dumped"] = sum(stacks.values())
        if args.jar:
            result["skein_status"] = cmd("skein status")
        result["ok"] = True
    except Exception as e:  # noqa: BLE001
        result["error"] = f"{type(e).__name__}: {e}"
        print("osc run failed:", result["error"], flush=True)
    finally:
        if rcon is not None and proc.poll() is None:
            try:
                rcon.command("stop")
            except Exception:  # noqa: BLE001
                pass
        try:
            proc.wait(timeout=180)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid, signal.SIGKILL)
            proc.wait()
        console.close()
        log = (srv / "logs" / "latest.log")
        if log.exists():
            shutil.copy2(log, args.out / "latest.log")
        means = [s["mean"] for s in result["samples"] if "mean" in s]
        result["mean_of_means_ms"] = round(sum(means) / len(means), 2) if means else None
        (args.out / "result.json").write_text(json.dumps(result, indent=1))
        print(f"done: mean {result['mean_of_means_ms']} ms/tick, tnt {result['tnt'][-1] if result['tnt'] else None}")
    return 0 if result.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main())
