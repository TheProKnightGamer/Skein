#!/usr/bin/env python3
"""Run the Skein benchmark matrix for one target: vanilla (A) against Skein (B), interleaved.

    python3 bench/run.py --target 1.21.1-neoforge \\
        --jar skein/1.21.1-neoforge/build/libs/skein-1.0.1-1.21.1-neoforge.jar --scenarios all

Each leg is one server start, at nice 10. If a `box-run` lock wrapper is on PATH, each leg runs under
`box-run --heavy` so nothing else heavy shares the machine; otherwise legs just run. Legs run
ABAB: for each repetition and each scenario, A then B. Leg files go to bench/work/<run-id>/ and the
summary to bench/results/<run-id>.json and .md. An interrupted run resumes with the same --run-id.

    python3 bench/run.py --provision-only --target 26.2-neoforge    # just install the server
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import shutil
import statistics
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

BENCH = Path(__file__).resolve().parent
sys.path.insert(0, str(BENCH))

import scenarios  # noqa: E402
from leg import SKEIN_CONFIG, HEAP_MAX, JVM_FLAGS, WARMUP_S, SAMPLES, INTERVAL_S, mem_available_gb, rel  # noqa: E402
from targets import TARGETS, java_for  # noqa: E402

MIN_FREE_GB = 12.0
LOCK_WAIT_S = 3000  # run.py is meant to run detached (setsid nohup), so no tool timeout applies


def log(msg: str) -> None:
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def provision(name: str) -> Path:
    t = TARGETS[name]
    srv = BENCH / "servers" / name
    if (srv / "run.sh").exists():
        return srv
    cache = BENCH / "cache"
    cache.mkdir(exist_ok=True)
    inst = cache / Path(t.installer_url).name
    if not inst.exists():
        log(f"downloading {t.installer_url}")
        tmp = inst.with_suffix(".part")
        req = urllib.request.Request(t.installer_url, headers={"User-Agent": "curl/8.5.0"})  # Forge's maven 403s urllib's UA
        with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
            f.write(r.read())
        tmp.rename(inst)
    srv.mkdir(parents=True, exist_ok=True)
    log(f"installing {name} server into {rel(srv)}")
    with open(srv / "install.log", "w") as out:
        rc = subprocess.run([java_for(t.java), "-jar", str(inst), "--installServer", str(srv)],
                            cwd=srv, stdout=out, stderr=subprocess.STDOUT).returncode
    if rc != 0 or not (srv / "run.sh").exists():
        raise SystemExit(f"installer failed for {name} (see {rel(srv)}/install.log)")
    return srv


def java_version(major: int) -> str:
    out = subprocess.run([java_for(major), "-version"], capture_output=True, text=True).stderr
    return out.splitlines()[0] if out else "?"


def run_leg(args, scen: str, cfg: str, rep: int, out: Path) -> dict | None:
    leg_cmd = [sys.executable, str(BENCH / "leg.py"), "--target", args.target, "--scenario", scen,
               "--config", cfg, "--rep", str(rep), "--out", str(out)]
    if shutil.which("box-run"):
        cmd = ["box-run", "--heavy", "--who", "skein-bench", "--wait", str(LOCK_WAIT_S), "--"] + leg_cmd
    else:
        cmd = ["nice", "-n", "10"] + leg_cmd
    if cfg == "B":
        cmd += ["--jar", str(Path(args.jar).resolve())]
    if args.warmup is not None:
        cmd += ["--warmup", str(args.warmup)]
    if args.samples is not None:
        cmd += ["--samples", str(args.samples)]
    failures = 0
    while True:
        while mem_available_gb() < MIN_FREE_GB:
            log(f"only {mem_available_gb():.1f} GB available (< {MIN_FREE_GB}); waiting")
            time.sleep(60)
        env = dict(os.environ, BOX_WHO="skein-bench")
        rc = subprocess.run(cmd, env=env).returncode
        if rc == 75:
            log("heavy lock busy for 9 min; retrying")
            continue
        if out.exists():
            leg = json.loads(out.read_text())
            if leg.get("ok"):
                return leg
        failures += 1
        log(f"leg {scen} {cfg}{rep} failed (rc={rc}, attempt {failures})")
        if failures >= 2:
            return json.loads(out.read_text()) if out.exists() else None
        time.sleep(20)


def summarize(meta: dict, legs: list[dict]) -> dict:
    summary = {}
    for scen in meta["scenarios"]:
        a = [l for l in legs if l["scenario"] == scen and l["config"] == "A" and l.get("ok")]
        b = [l for l in legs if l["scenario"] == scen and l["config"] == "B" and l.get("ok")]
        if not a or not b:
            summary[scen] = {"incomplete": True, "a_legs": len(a), "b_legs": len(b)}
            continue
        am = [l["median_ms"] for l in a]
        bm = [l["median_ms"] for l in b]
        va, vb = statistics.median(am), statistics.median(bm)
        dims = sorted({k for l in a + b for k in l.get("dims_median_ms", {})})
        summary[scen] = {
            "vanilla_ms": round(va, 3), "skein_ms": round(vb, 3), "speedup": round(va / vb, 3) if vb else None,
            "vanilla_reps": am, "skein_reps": bm,
            "vanilla_spread_pct": round(100 * (max(am) - min(am)) / va, 1) if va else None,
            "skein_spread_pct": round(100 * (max(bm) - min(bm)) / vb, 1) if vb else None,
            "dims": {d: {"vanilla_ms": round(statistics.median([l["dims_median_ms"][d] for l in a
                                                                  if d in l.get("dims_median_ms", {})] or [0]), 3),
                         "skein_ms": round(statistics.median([l["dims_median_ms"][d] for l in b
                                                               if d in l.get("dims_median_ms", {})] or [0]), 3)}
                     for d in dims},
            "checks_pass": all(l.get("checks", {}).get("pass") for l in a + b),
            "skein_hooks_missing": max(((l.get("skein_hooks") or {}).get("missing", -1) for l in b), default=-1),
            "log_errors": {"A": sum(l.get("log", {}).get("error_lines", 0) for l in a),
                           "B": sum(l.get("log", {}).get("error_lines", 0) for l in b)},
        }
    return summary


def markdown(meta: dict, summary: dict, legs: list[dict]) -> str:
    lines = [f"# Skein benchmark: {meta['target']}", "",
             f"- run: `{meta['run_id']}`, {meta['finished']}",
             f"- loader build {meta['loader_build']}, {meta['java']}",
             f"- jar: `{meta['jar']}` (sha256 {meta['jar_sha256'][:12]})",
             f"- Skein config: defaults, threadCount = 8. Heap -Xmx{meta['heap']}. "
             f"Warm-up {meta['warmup_s']} s, then {meta['samples']} samples {meta['interval_s']} s apart. "
             f"Loader command: `/{meta['tps_command']}`",
             f"- {meta['reps']} repetitions, legs interleaved A,B per scenario; medians of per-leg medians", "",
             "| scenario | vanilla ms/tick | skein ms/tick | speedup | spread A / B | checks | Skein hooks missing |",
             "| --- | --- | --- | --- | --- | --- | --- |"]
    for scen, s in summary.items():
        if s.get("incomplete"):
            lines.append(f"| {scen} | incomplete ({s['a_legs']} A, {s['b_legs']} B legs) | | | | |")
            continue
        lines.append(f"| {scen} | {s['vanilla_ms']:.2f} | {s['skein_ms']:.2f} | **{s['speedup']:.2f}x** | "
                     f"{s['vanilla_spread_pct']}% / {s['skein_spread_pct']}% | "
                     f"{'pass' if s['checks_pass'] else 'FAIL'} | {s['skein_hooks_missing']} |")
    lines += ["", "Per leg (median ms/tick, load average before/after, correctness):", "",
              "| scenario | leg | ms/tick | loadavg | checks | log errors |", "| --- | --- | --- | --- | --- | --- |"]
    for l in legs:
        c = l.get("checks", {})
        lines.append(f"| {l['scenario']} | {l['config']}{l['rep']} | {l.get('median_ms', '-')} | "
                     f"{l.get('loadavg_before', ['?'])[0]} / {l.get('loadavg_after', ['?'])[0]} | "
                     f"{'pass' if c.get('pass') else ('FAIL ' + str({k: c.get(k) for k in ('entities_after', 'items_after', 'bots_online_after')}) if c else l.get('error', '-'))} | "
                     f"{l.get('log', {}).get('error_lines', '-')} |")
    return "\n".join(lines) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--target", required=True, choices=sorted(TARGETS))
    ap.add_argument("--jar", help="the Skein jar to measure (config B)")
    ap.add_argument("--scenarios", default="all", help=f"comma list or 'all' ({','.join(scenarios.SCENARIOS)})")
    ap.add_argument("--reps", type=int, default=3)
    ap.add_argument("--run-id", help="resume/label; default <target>-<date>")
    ap.add_argument("--provision-only", action="store_true")
    ap.add_argument("--warmup", type=int, help=f"seconds (default {WARMUP_S})")
    ap.add_argument("--samples", type=int, help=f"(default {SAMPLES})")
    args = ap.parse_args()

    t = TARGETS[args.target]
    provision(args.target)
    if args.provision_only:
        return 0
    if not args.jar or not Path(args.jar).is_file():
        raise SystemExit("--jar must name an existing Skein jar")
    scen = scenarios.SCENARIOS if args.scenarios == "all" else args.scenarios.split(",")
    run_id = args.run_id or f"{args.target}-{time.strftime('%Y%m%d-%H%M')}"
    work = BENCH / "work" / run_id
    work.mkdir(parents=True, exist_ok=True)
    # Measure a frozen copy, so a rebuild during a multi-hour run cannot mix two builds into one result.
    source_jar = Path(args.jar).resolve()
    jar = work / source_jar.name
    if not jar.exists() or hashlib.sha256(jar.read_bytes()).digest() != hashlib.sha256(source_jar.read_bytes()).digest():
        shutil.copy2(source_jar, jar)
    args.jar = str(jar)
    sha = hashlib.sha256(jar.read_bytes()).hexdigest()
    meta = {
        "run_id": run_id, "target": t.name, "mc": t.mc, "loader": t.loader, "loader_build": t.build,
        "java": java_version(t.java), "jar": rel(source_jar), "jar_sha256": sha, "scenarios": scen, "reps": args.reps,
        "skein_config": SKEIN_CONFIG, "heap": HEAP_MAX, "jvm_flags": JVM_FLAGS,
        "warmup_s": args.warmup or WARMUP_S, "samples": args.samples or SAMPLES, "interval_s": INTERVAL_S,
        "tps_command": t.tps_command, "cpu": platform.processor() or platform.machine(), "cpus": os.cpu_count(),
    }
    try:
        meta["cpu"] = next(l.split(":", 1)[1].strip() for l in open("/proc/cpuinfo") if l.startswith("model name"))
    except (OSError, StopIteration):
        pass

    legs = []
    for rep in range(1, args.reps + 1):
        for s in scen:
            for cfg in ("A", "B"):
                out = work / f"{s}-{cfg}{rep}.json"
                if out.exists() and json.loads(out.read_text()).get("ok") and \
                        (cfg == "A" or json.loads(out.read_text()).get("jar_sha256") == sha):
                    legs.append(json.loads(out.read_text()))
                    continue
                log(f"leg {s} {cfg}{rep}")
                leg = run_leg(args, s, cfg, rep, out)
                if leg:
                    legs.append(leg)
                    log(f"  -> {leg.get('median_ms')} ms/tick, checks={leg.get('checks', {}).get('pass')}, "
                        f"ok={leg.get('ok')}")
    meta["finished"] = time.strftime("%Y-%m-%d %H:%M")
    summary = summarize(meta, legs)
    res = BENCH / "results"
    res.mkdir(exist_ok=True)
    (res / f"{run_id}.json").write_text(json.dumps({"meta": meta, "summary": summary, "legs": legs}, indent=1))
    (res / f"{run_id}.md").write_text(markdown(meta, summary, legs))
    log(f"wrote {rel(res / (run_id + '.md'))}")
    print(markdown(meta, summary, legs))
    return 0


if __name__ == "__main__":
    sys.exit(main())
