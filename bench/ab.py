#!/usr/bin/env python3
"""Compare two Skein builds on one target: interleaved legs, paired ratios.

    python3 bench/ab.py --target 1.21.1-neoforge --old bench/jars/old.jar --new <new.jar> \
        --scenarios entities,mixed,quiet --reps 3 --out bench/work/ab-<name>

run.py answers "how much faster than vanilla"; this answers "is the new build faster than the old
one". Every leg is config B (leg.py), so both builds get the same world, players and threadCount.
Within a repetition the two builds run back to back, and the order alternates between repetitions
(old,new then new,old), so load that drifts over a leg's few minutes counts against each build
equally. Each scenario is reported as the median of the per-pair ratios new/old, next to both
builds' medians and the phase lines of /skein status. --vanilla adds one config-A leg per
scenario and repetition, for the speedup over vanilla.

Legs that already have a result file in --out are kept, so an interrupted comparison resumes.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import statistics
import subprocess
import sys
import time
from pathlib import Path

BENCH = Path(__file__).resolve().parent
sys.path.insert(0, str(BENCH))

import scenarios  # noqa: E402
from targets import TARGETS  # noqa: E402


def leg(args, scen: str, cfg: str, jar: Path | None, rep: int, out: Path) -> dict | None:
    if out.exists():
        return json.loads(out.read_text())
    cmd = ["nice", "-n", str(args.nice), sys.executable, str(BENCH / "leg.py"), "--target", args.target,
           "--scenario", scen, "--config", "A" if jar is None else "B", "--rep", str(rep), "--out", str(out)]
    if jar is not None:
        cmd += ["--jar", str(jar)]
    if args.warmup:
        cmd += ["--warmup", str(args.warmup)]
    if args.samples:
        cmd += ["--samples", str(args.samples)]
    for e in args.extra_mod:
        cmd += ["--extra-mod", str(e)]
    if args.max_load:
        # Other work on the box swamps a difference of a few per cent: wait for a quieter moment,
        # but not forever -- a leg that ran anyway says so in its loadavg_before.
        waited = 0
        while os.getloadavg()[0] > args.max_load and waited < args.max_wait:
            time.sleep(15)
            waited += 15
        if waited:
            print(f"  waited {waited} s for load {os.getloadavg()[0]:.1f} <= {args.max_load}", flush=True)
    print(f"[{time.strftime('%H:%M:%S')}] {scen} {cfg}", flush=True)
    subprocess.run(cmd)
    return json.loads(out.read_text()) if out.exists() else None


def phase_lines(status: str | None) -> list[str]:
    if not status:
        return []
    return [l.strip() for l in status.splitlines()
            if re.match(r"\s*(dimension overlap|random ticks|entities|block entities|minecraft:)", l)]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--target", required=True, choices=sorted(TARGETS))
    ap.add_argument("--old", required=True, type=Path)
    ap.add_argument("--new", required=True, type=Path)
    ap.add_argument("--scenarios", default="entities,hoppers,random,mixed,quiet")
    ap.add_argument("--reps", type=int, default=3)
    ap.add_argument("--vanilla", action="store_true", help="also run config A each repetition")
    ap.add_argument("--warmup", type=int)
    ap.add_argument("--samples", type=int)
    ap.add_argument("--extra-mod", action="append", default=[], type=Path)
    ap.add_argument("--nice", type=int, default=10)
    ap.add_argument("--max-load", type=float, help="wait before each leg until the 1-minute load average is below this")
    ap.add_argument("--max-wait", type=int, default=1200, help="seconds to wait for --max-load (default 1200)")
    ap.add_argument("--out", required=True, type=Path)
    args = ap.parse_args()

    scens = [s for s in args.scenarios.split(",") if s]
    for s in scens:
        if s not in scenarios.SCENARIOS:
            ap.error(f"unknown scenario {s}")
    args.out.mkdir(parents=True, exist_ok=True)
    # measure frozen copies, so a rebuild during the run cannot mix builds
    jars = {}
    for name, src in (("old", args.old), ("new", args.new)):
        dst = args.out / f"{name}.jar"
        if not dst.exists():
            shutil.copy2(src, dst)
        jars[name] = dst

    legs: dict[str, dict[str, list[dict]]] = {s: {"old": [], "new": [], "vanilla": []} for s in scens}
    for rep in range(1, args.reps + 1):
        order = ["old", "new"] if rep % 2 else ["new", "old"]
        for s in scens:
            for name in order:
                r = leg(args, s, name, jars[name], rep, args.out / f"{s}-{name}{rep}.json")
                if r:
                    r["rep"] = rep
                    legs[s][name].append(r)
            if args.vanilla:
                r = leg(args, s, "vanilla", None, rep, args.out / f"{s}-vanilla{rep}.json")
                if r:
                    r["rep"] = rep
                    legs[s]["vanilla"].append(r)

    summary = {}
    md = [f"# Skein A/B: {args.target}", "",
          f"- old: `{args.old.name}`, new: `{args.new.name}`; {args.reps} repetitions, order alternating",
          f"- ratio = new ms / old ms per back-to-back pair; below 1 means the new build is faster", "",
          "| scenario | old ms | new ms | median pair ratio | pair ratios | pair load (1 min) | vanilla ms | checks |",
          "| --- | --- | --- | --- | --- | --- | --- | --- |"]
    for s in scens:
        old = {r["rep"]: r for r in legs[s]["old"] if r.get("ok")}
        new = {r["rep"]: r for r in legs[s]["new"] if r.get("ok")}
        ratios = [round(new[k]["median_ms"] / old[k]["median_ms"], 3) for k in sorted(old) if k in new]
        loads = [max(old[k].get("loadavg_before", [0])[0], new[k].get("loadavg_before", [0])[0])
                 for k in sorted(old) if k in new]
        med = lambda rs: round(statistics.median([r["median_ms"] for r in rs]), 3) if rs else None  # noqa: E731
        van = [r for r in legs[s]["vanilla"] if r.get("ok")]
        checks = all(r.get("checks", {}).get("pass") for r in legs[s]["old"] + legs[s]["new"] + legs[s]["vanilla"])
        summary[s] = {"old_ms": med(list(old.values())), "new_ms": med(list(new.values())), "pair_ratios": ratios,
                      "median_ratio": round(statistics.median(ratios), 3) if ratios else None,
                      "vanilla_ms": med(van), "checks_pass": checks, "pair_max_load": loads,
                      "old_phases": [phase_lines(r.get("skein_status")) for r in old.values()],
                      "new_phases": [phase_lines(r.get("skein_status")) for r in new.values()]}
        x = summary[s]
        md.append(f"| {s} | {x['old_ms']} | {x['new_ms']} | **{x['median_ratio']}** | {x['pair_ratios']} | "
                  f"{[round(l) for l in loads]} | {x['vanilla_ms'] or '-'} | {'pass' if checks else 'FAIL'} |")
    md.append("")
    for s in scens:
        md.append(f"## {s}")
        for name in ("old", "new"):
            for i, lines in enumerate(summary[s][f"{name}_phases"], 1):
                md.append(f"- {name} #{i}: " + "; ".join(lines))
        md.append("")
    (args.out / "summary.json").write_text(json.dumps(summary, indent=1))
    (args.out / "summary.md").write_text("\n".join(md) + "\n")
    print("\n".join(md[:6 + len(scens)]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
