"""Turn a CurseForge instance into two dedicated servers: one without Skein, one with it.

Usage:
  python3 -I bench/packs/packserver.py --instance <instance dir> --target <bench target> \
      --skein <skein jar> --out <work dir> [--drop <jar> ...] [--replace <old jar>=<new jar> ...]

Both servers start from the bench harness's installed loader (bench/servers/<target>)
and get the instance's mods, config and defaultconfigs, a flat world and the loopback
settings below. <out>/plain has no Skein; <out>/skein is the same plus the Skein jar.
--drop leaves a jar out of both, --replace swaps one jar for another in both.

Then boot the plain server with packboot.py --drop-failing, copy its removed.txt
over with --apply-removed, and boot the Skein server without --drop-failing, so the
two run the same mods and anything that fails only with Skein stands out.
"""
import argparse, os, shutil, sys

HERE = os.path.dirname(os.path.abspath(__file__))
BENCH = os.path.dirname(HERE)

PROPERTIES = """server-ip=127.0.0.1
server-port=0
online-mode=false
enable-rcon=false
level-name=world
level-type=minecraft\\:flat
view-distance=4
simulation-distance=4
spawn-protection=0
max-tick-time=-1
"""

ap = argparse.ArgumentParser()
ap.add_argument('--instance', required=True)
ap.add_argument('--target', required=True)
ap.add_argument('--skein', required=True)
ap.add_argument('--out', required=True)
ap.add_argument('--drop', action='append', default=[])
ap.add_argument('--replace', action='append', default=[])
ap.add_argument('--apply-removed', help="removed.txt from a booted plain server: drop those from the Skein server")
a = ap.parse_args()

if a.apply_removed:
    skein = os.path.join(a.out, 'skein')
    os.makedirs(os.path.join(skein, 'removed'), exist_ok=True)
    for jar in open(a.apply_removed).read().split():
        src = os.path.join(skein, 'mods', jar)
        if os.path.exists(src):
            shutil.move(src, os.path.join(skein, 'removed', jar))
            print('dropped', jar)
    sys.exit(0)

base = os.path.join(BENCH, 'servers', a.target)
replace = dict(r.split('=', 1) for r in a.replace)
for name in ('plain', 'skein'):
    srv = os.path.join(a.out, name)
    shutil.rmtree(srv, ignore_errors=True)
    # Only the top level: libraries/ has its own "mods" and "config" directories.
    skip = {'world', 'logs', 'mods', 'crash-reports', 'config', 'defaultconfigs', 'bench-console.log'}
    shutil.copytree(base, srv, ignore=lambda d, names: skip & set(names) if os.path.samefile(d, base) else set())
    os.makedirs(os.path.join(srv, 'mods'))
    for jar in sorted(os.listdir(os.path.join(a.instance, 'mods'))):
        if not jar.endswith('.jar') or jar in a.drop or jar.startswith('skein-'):
            continue
        src = os.path.join(a.instance, 'mods', jar)
        if jar in replace:
            src = replace[jar]
            jar = os.path.basename(src)
        shutil.copy2(src, os.path.join(srv, 'mods', jar))
    for d in ('config', 'defaultconfigs'):
        if os.path.isdir(os.path.join(a.instance, d)):
            shutil.copytree(os.path.join(a.instance, d), os.path.join(srv, d))
    if name == 'skein':
        shutil.copy2(a.skein, os.path.join(srv, 'mods', os.path.basename(a.skein)))
    with open(os.path.join(srv, 'server.properties'), 'w') as f:
        f.write(PROPERTIES)
    with open(os.path.join(srv, 'eula.txt'), 'w') as f:
        f.write('eula=true\n')
    print(name, len(os.listdir(os.path.join(srv, 'mods'))), 'mods')
