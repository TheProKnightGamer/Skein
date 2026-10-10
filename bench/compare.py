#!/usr/bin/env python3
"""Check a port's benchmark results against the 1.21.1-neoforge baseline.

    python3 bench/compare.py bench/results/1.21.1-neoforge-<id>.json bench/results/<port>-<id>.json

Acceptance ("the same speed or better"):
  * every scenario: port speedup (vanilla/skein on its own version) >= 0.95 x the baseline's speedup
  * quiet: port skein ms/tick <= 1.03 x port vanilla ms/tick (Skein must not cost a quiet server)
  * every leg's correctness checks passed (entity counts, item conservation, players still online)
  * /skein status reports 0 missing hooks

Absolute ms/tick is not compared across versions: vanilla itself changed between them. Each version
is judged by what Skein does for that version's own vanilla. Exit code 0 = accepted, 1 = rejected.
"""
from __future__ import annotations

import json
import sys

SPEEDUP_FLOOR = 0.95
QUIET_CEILING = 1.03


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    base = json.load(open(sys.argv[1]))
    port = json.load(open(sys.argv[2]))
    bt, pt = base["meta"]["target"], port["meta"]["target"]
    print(f"baseline {bt} ({base['meta']['run_id']})  vs  port {pt} ({port['meta']['run_id']})\n")
    print(f"{'scenario':10} {'base x':>8} {'port x':>8} {'ratio':>7} {'port A ms':>10} {'port B ms':>10}  verdict")
    ok = True
    for scen, b in base["summary"].items():
        p = port["summary"].get(scen)
        if p is None or p.get("incomplete") or b.get("incomplete"):
            print(f"{scen:10} {'-':>8} {'-':>8} {'-':>7} {'-':>10} {'-':>10}  MISSING")
            ok = False
            continue
        ratio = p["speedup"] / b["speedup"]
        reasons = []
        if ratio < SPEEDUP_FLOOR:
            reasons.append(f"speedup {ratio:.2f}x of baseline (< {SPEEDUP_FLOOR})")
        if scen == "quiet" and p["skein_ms"] > QUIET_CEILING * p["vanilla_ms"]:
            reasons.append(f"quiet: skein {p['skein_ms']:.2f} > {QUIET_CEILING} x vanilla {p['vanilla_ms']:.2f}")
        if not p.get("checks_pass"):
            reasons.append("correctness checks failed")
        if p.get("skein_hooks_missing", 0) > 0:
            reasons.append(f"{p['skein_hooks_missing']} Skein hooks missing (features switched off)")
        verdict = "ok" if not reasons else "FAIL: " + "; ".join(reasons)
        ok &= not reasons
        print(f"{scen:10} {b['speedup']:>8.2f} {p['speedup']:>8.2f} {ratio:>7.2f} {p['vanilla_ms']:>10.2f} "
              f"{p['skein_ms']:>10.2f}  {verdict}")
    print("\nACCEPTED" if ok else "\nREJECTED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
