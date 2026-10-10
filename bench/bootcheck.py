#!/usr/bin/env python3
"""Boot one target with Skein and other mods, ask /skein status, stop, and summarise.

    python3 bench/bootcheck.py --target 1.20.1-forge --jar <skein.jar> --extra-mod <jar> ... --out check.json

For mods that a bot cannot join past (any mod a vanilla client would need), so leg.py's
scenarios cannot run with them: this checks that Skein starts beside them, which of its
hooks applied, what it switched off, and what the log says, without players. Uses the
harness's installed server for the target and leaves it reset for the next leg.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import signal
import subprocess
import sys
import time
from pathlib import Path

BENCH = Path(__file__).resolve().parent
sys.path.insert(0, str(BENCH))

from leg import launch_argv, reset  # noqa: E402
from targets import TARGETS, java_for  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--target", required=True, choices=sorted(TARGETS))
    ap.add_argument("--jar", required=True, type=Path)
    ap.add_argument("--extra-mod", action="append", default=[], type=Path)
    ap.add_argument("--ticks", type=int, default=30, help="seconds to run before asking for status")
    ap.add_argument("--out", required=True, type=Path)
    args = ap.parse_args()

    t = TARGETS[args.target]
    srv = BENCH / "servers" / args.target
    reset(srv, "B", args.jar, args.extra_mod)
    console = srv / "bench-console.log"
    out = open(console, "w")
    proc = subprocess.Popen(launch_argv(srv, java_for(t.java)), cwd=srv, stdin=subprocess.PIPE, stdout=out,
                            stderr=subprocess.STDOUT, start_new_session=True)
    result = {"target": args.target, "jar": args.jar.name, "extra_mods": [e.name for e in args.extra_mod]}

    def say(cmd: str) -> None:
        if proc.poll() is None:
            try:
                proc.stdin.write((cmd + "\n").encode())
                proc.stdin.flush()
            except OSError:
                pass

    deadline = time.monotonic() + 300
    while time.monotonic() < deadline and proc.poll() is None:
        if "Done (" in console.read_text(errors="replace"):
            break
        time.sleep(1)
    result["started"] = "Done (" in console.read_text(errors="replace") and proc.poll() is None
    if result["started"]:
        time.sleep(args.ticks)
        say("skein status")
        time.sleep(5)
        say("stop")
    try:
        proc.wait(120)
    except subprocess.TimeoutExpired:
        os.killpg(proc.pid, signal.SIGKILL)
        proc.wait()

    log_path = srv / "logs" / "latest.log"
    text = (log_path if log_path.exists() else console).read_text(errors="replace")
    lines = text.splitlines()
    result["hooks"] = next((m.group(0) for l in lines
                            for m in [re.search(r"hooks: \d+ hooks checked, \d+ missing[^\n]*|\d+ hooks checked, \d+ missing[^\n]*", l)]
                            if m), None)
    result["missing_hooks"] = [re.sub(r"^.*?missing hook: ", "", l) for l in lines if "missing hook:" in l]
    result["skein_warnings"] = [re.sub(r"^\[[^\]]*\] ", "", l)[:300] for l in lines
                                if re.search(r"/(WARN|ERROR)\]", l) and "Skein" in l]
    result["errors"] = [re.sub(r"^\[[^\]]*\] ", "", l)[:300] for l in lines if re.search(r"/(ERROR|FATAL)\]", l)]
    result["phases"] = next((re.sub(r"^.*?phases: ", "", l) for l in lines if "  phases: " in l), None)
    result["crash_reports"] = sorted(p.name for p in (srv / "crash-reports").glob("*.txt")) \
        if (srv / "crash-reports").is_dir() else []
    args.out.write_text(json.dumps(result, indent=1))
    print(f"{args.target}: started={result['started']} {result['hooks']} phases={result['phases']} "
          f"errors={len(result['errors'])} skein_warnings={len(result['skein_warnings'])} "
          f"crashes={len(result['crash_reports'])}")
    for line in result["missing_hooks"][:10]:
        print("  missing hook:", line[:200])
    return 0 if result["started"] and not result["crash_reports"] else 1


if __name__ == "__main__":
    sys.exit(main())
