"""Errors a pack only shows with Skein installed.

Usage: python3 -I bench/packs/compare.py <plain/probe.json> <skein/probe.json>

Error lines are grouped with numbers blanked out. A group that appears with Skein and
not without it, or many times more often, is listed first; those are Skein's to fix.
"""
import json, sys

plain, skein = (json.load(open(p)) for p in sys.argv[1:3])
pe, se = plain['errors'], skein['errors']
only = sorted(((n, m) for m, n in se.items() if m not in pe), reverse=True)
more = sorted(((n, pe[m], m) for m, n in se.items() if m in pe and n > 3 * pe[m] + 5), reverse=True)
print(f"without Skein: {sum(pe.values())} error lines in {len(pe)} groups, alive {plain['alive']}, "
      f"behind {plain['cant_keep_up']}")
print(f"with Skein:    {sum(se.values())} error lines in {len(se)} groups, alive {skein['alive']}, "
      f"behind {skein['cant_keep_up']}")
print(f'\nonly with Skein ({len(only)} groups):')
for n, m in only[:60]:
    print(f'  {n:6} {m}')
print(f'\nfar more often with Skein ({len(more)} groups):')
for n, base, m in more[:30]:
    print(f'  {n:6} (vs {base}) {m}')
print(f"\nSkein's own warnings and errors: {len(skein['skein_lines'])}")
for line in skein['skein_lines'][:30]:
    print('  ' + line[:300])
