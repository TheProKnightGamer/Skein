#!/usr/bin/env python3
"""Summarise CPU samples from a Flight Recorder file (leg.py --jfr) by thread group and method.

    python3 bench/jfrsum.py <file.jfr> [--top 40] [--match skein] [--java <jdk bin/java>]

Thread groups: the server thread, Skein's workers, and everything else. For each group: the share
of samples, the methods most often on top of the stack (self), and the methods most often anywhere
in it (inclusive, each counted once per sample). --match limits the inclusive list to frames whose
name contains the string, e.g. "skein" for Skein's own code.
"""
from __future__ import annotations

import argparse
import collections
import re
import shutil
import subprocess
import sys
from pathlib import Path


def group_of(thread: str) -> str:
    if thread.startswith("Server thread"):
        return "server"
    if thread.startswith("Skein"):
        return "workers"
    return "other"


def frames_of(stream):
    """Yield (thread, [frames top-first]) for each jdk.ExecutionSample in `jfr print` output."""
    thread, frames, in_stack = None, [], False
    for line in stream:
        s = line.strip()
        if s.startswith("jdk.ExecutionSample"):
            thread, frames, in_stack = None, [], False
        elif s.startswith("sampledThread = "):
            m = re.match(r'sampledThread = "(.*)" \(', s)
            thread = m.group(1) if m else s
        elif s.startswith("stackTrace = ["):
            in_stack = True
        elif in_stack and s == "]":
            in_stack = False
        elif in_stack and s and s != "...":
            frames.append(re.sub(r"\s+line: \d+.*$", "", s))
        elif s == "}" and thread is not None:
            yield thread, frames
            thread = None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("jfr", type=Path)
    ap.add_argument("--top", type=int, default=40)
    ap.add_argument("--match", default=None)
    ap.add_argument("--depth", type=int, default=96)
    ap.add_argument("--java", default=None, help="a JDK's bin/java; its jfr tool is used")
    args = ap.parse_args()
    jfr = str(Path(args.java).with_name("jfr")) if args.java else (shutil.which("jfr") or "jfr")
    p = subprocess.Popen([jfr, "print", "--events", "jdk.ExecutionSample", "--stack-depth", str(args.depth),
                          str(args.jfr)], stdout=subprocess.PIPE, text=True, errors="replace")
    total = collections.Counter()
    selfc = collections.defaultdict(collections.Counter)
    incl = collections.defaultdict(collections.Counter)
    threads = collections.Counter()
    for thread, frames in frames_of(p.stdout):
        g = group_of(thread)
        total[g] += 1
        threads[thread] += 1
        if frames:
            selfc[g][frames[0]] += 1
        for f in set(frames):
            if args.match is None or args.match in f:
                incl[g][f] += 1
    p.wait()
    n = sum(total.values()) or 1
    print(f"{n} samples: " + ", ".join(f"{g} {c} ({100 * c / n:.1f}%)" for g, c in total.most_common()))
    print("threads:", ", ".join(f"{t}={c}" for t, c in threads.most_common(12)))
    for g in ("server", "workers", "other"):
        if not total[g]:
            continue
        print(f"\n== {g}: self (top {args.top})")
        for f, c in selfc[g].most_common(args.top):
            print(f"  {100 * c / total[g]:5.1f}%  {f}")
        print(f"== {g}: inclusive{' matching ' + args.match if args.match else ''} (top {args.top})")
        for f, c in incl[g].most_common(args.top):
            print(f"  {100 * c / total[g]:5.1f}%  {f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
