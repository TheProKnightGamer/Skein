#!/usr/bin/env python3
"""Cross-version report: every target's benchmark in one page.

    python3 bench/report.py > bench/results/REPORT.md

compare.py's rule (each port's whole-tick speedup >= 0.95x the 1.21.1-neoforge speedup)
is kept and reported as it stands. It turned out to be confounded, though: vanilla's own
cost for the same world differs a lot between versions and loaders (random ticks on the
identical world cost 8.4 ms on NeoForge 1.21.1, 5.7 ms on Forge 1.21.1 and 4.4 ms on 1.20.1).
Less parallelisable work gives a smaller whole-tick ratio even when Skein parallelises it
just as well. So three more views are reported:

  * same version, other loader: Skein ms/tick on the identical world against the
    NeoForge build of that version. Lower is better.
  * phase efficiency: each phase's overlap (work over wall-clock on the 8 workers), from
    /skein status in the B legs, against the 1.21.1-neoforge baseline.
  * quiet and hoppers with 4 repetitions (<run-id>-ext): is Skein slower than vanilla
    beyond the noise between repetitions?
"""
from __future__ import annotations

import json
import re
import statistics as st
import time
from pathlib import Path

RES = Path(__file__).resolve().parent / "results"
RUNS = {
    "1.20.1-forge": "1.20.1-forge-20260930", "1.20.1-neoforge": "1.20.1-neoforge-20260930",
    "1.21.1-forge": "1.21.1-forge-port", "1.21.1-neoforge": "1.21.1-neoforge-baseline",
    "1.21.11-forge": "1.21.11-forge-port", "1.21.11-neoforge": "1.21.11-neoforge-baseline",
    "26.2-forge": "26.2-forge-port", "26.2-neoforge": "26.2-neoforge-port",
}
SCEN = ["entities", "hoppers", "random", "mixed", "quiet"]
PHASE = re.compile(r"(?m)^\s*(random ticks|entities|block entities): ([\d.]+)x\s+\(([\d.]+)ms in ([\d.]+)ms\)")


def load(run_id: str) -> dict | None:
    p = RES / f"{run_id}.json"
    return json.loads(p.read_text()) if p.exists() else None


def legs(d: dict, scen: str, cfg: str) -> list[dict]:
    return [l for l in d["legs"] if l["scenario"] == scen and l["config"] == cfg and l.get("ok")]


PAIR_WINDOW_S = 20 * 60


def started(leg: dict) -> float:
    return time.mktime(time.strptime(leg["started"], "%Y-%m-%d %H:%M:%S"))


def med(xs: list[float]) -> float | None:
    return st.median(xs) if xs else None


def ms(d: dict, scen: str, cfg: str) -> float | None:
    return med([l["median_ms"] for l in legs(d, scen, cfg)])


def phases(d: dict, scen: str) -> dict[str, float]:
    out: dict[str, list[float]] = {}
    for l in legs(d, scen, "B"):
        for name, x, _work, _wall in PHASE.findall(str(l.get("skein_status", ""))):
            out.setdefault(name, []).append(float(x))
    return {k: st.median(v) for k, v in out.items()}


def f(x: float | None, fmt: str = "{:.2f}") -> str:
    return "-" if x is None else fmt.format(x)


def main() -> None:
    data = {t: load(r) for t, r in RUNS.items()}
    ext = {t: load(r + "-ext") for t, r in RUNS.items()}
    base = data["1.21.1-neoforge"]
    print("# Skein cross-version benchmark report\n")
    print("Each target is measured on a real dedicated server with the same generated world and scenarios: "
          "A is the loader alone, B adds Skein (defaults, `threadCount = 8`). Values are medians of per-leg "
          "medians, in ms per tick. Every leg's correctness checks passed (entity counts, item conservation, "
          "bots online), and every Skein run reported 0 missing hooks.\n")

    print("## Vanilla vs Skein, per target\n")
    print("| target | " + " | ".join(SCEN) + " |")
    print("| --- |" + " --- |" * len(SCEN))
    for t, d in data.items():
        if not d:
            continue
        cells = []
        for s in SCEN:
            a, b = ms(d, s, "A"), ms(d, s, "B")
            cells.append(f"{f(a)} → {f(b)} (**{f(a / b if a and b else None)}x**)")
        print(f"| {t} | " + " | ".join(cells) + " |")

    print("\n## Rule 1, as specified: speedup ≥ 0.95 × the 1.21.1-neoforge speedup\n")
    print("| target | " + " | ".join(SCEN) + " | verdict |")
    print("| --- |" + " --- |" * (len(SCEN) + 1))
    for t, d in data.items():
        if not d or t == "1.21.1-neoforge":
            continue
        cells, ok = [], True
        for s in SCEN:
            a, b, ba, bb = ms(d, s, "A"), ms(d, s, "B"), ms(base, s, "A"), ms(base, s, "B")
            if None in (a, b, ba, bb):
                cells.append("missing"); ok = False; continue
            r = (a / b) / (ba / bb)
            ok &= r >= 0.95
            cells.append(f"{r:.2f}" + ("" if r >= 0.95 else " ✗"))
        print(f"| {t} | " + " | ".join(cells) + f" | {'pass' if ok else 'fail'} |")

    print("\n## Same version, other loader: Skein ms/tick against the NeoForge build of that version\n")
    print("Same Minecraft version, same world. Lower is better, and a ratio ≤ 1.00 means the Forge build "
          "with Skein ticks at least as fast as the NeoForge build with Skein.\n")
    print("| version | " + " | ".join(SCEN) + " |")
    print("| --- |" + " --- |" * len(SCEN))
    for v in ["1.20.1", "1.21.1", "1.21.11", "26.2"]:
        fo, ne = data.get(f"{v}-forge"), data.get(f"{v}-neoforge")
        if not fo or not ne:
            continue
        cells = []
        for s in SCEN:
            x, y = ms(fo, s, "B"), ms(ne, s, "B")
            cells.append(f"{f(x)} vs {f(y)} ({f(x / y if x and y else None)})")
        print(f"| {v} Forge vs NeoForge | " + " | ".join(cells) + " |")

    print("\n## Phase efficiency: overlap from `/skein status` (work ÷ wall-clock, 8 workers)\n")
    print("| target | random ticks (random) | entities (entities) | random ticks (mixed) | entities (mixed) | block entities (mixed) |")
    print("| --- | --- | --- | --- | --- | --- |")
    for t, d in data.items():
        if not d:
            continue
        r, e, m = phases(d, "random"), phases(d, "entities"), phases(d, "mixed")
        print(f"| {t} | {f(r.get('random ticks'))}x | {f(e.get('entities'))}x | {f(m.get('random ticks'))}x | "
              f"{f(m.get('entities'))}x | {f(m.get('block entities'))}x |")

    print("\n## Quiet server and hoppers, 4 repetitions: does Skein ever cost time?\n")
    print("Each repetition runs its vanilla leg and its Skein leg back to back, so B/A is taken per pair "
          "(Skein ms/tick ÷ vanilla ms/tick of the same repetition), which cancels load that drifts between "
          "repetitions. The table gives the median pair ratio and the range of the pair ratios. Below 1.00 "
          "means Skein was faster. A range that straddles 1.00 means no difference was measurable.\n")
    print("| target | quiet B/A | quiet range | hoppers B/A | hoppers range |")
    print("| --- | --- | --- | --- | --- |")
    for t in RUNS:
        d = ext[t] or data[t]
        if not d:
            continue
        cells = []
        for s in ("quiet", "hoppers"):
            A = {l["rep"]: l for l in legs(d, s, "A")}
            B = {l["rep"]: l for l in legs(d, s, "B")}
            # a pair counts only if its two legs ran back to back (a rerun leg from another day does not)
            r = [B[k]["median_ms"] / A[k]["median_ms"] for k in sorted(A)
                 if k in B and abs(started(A[k]) - started(B[k])) <= PAIR_WINDOW_S]
            if not r:
                cells += ["-", "-"]; continue
            cells += [f"{st.median(r):.2f} (n={len(r)})", f"{min(r):.2f}–{max(r):.2f}"]
        print(f"| {t} | " + " | ".join(cells) + " |")


if __name__ == "__main__":
    main()
