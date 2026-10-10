"""Write a datapack that fills a force-loaded area with every modded block and entity.

Usage: python3 -I bench/packs/genprobe.py <mods dir> <datapack dir> [pack_format]

Block ids come from each jar's assets/<ns>/blockstates/*.json (jar-in-jar too),
entity ids from the entity.<ns>.<name> keys of its en_us lang file. Vanilla ids are
skipped. Writes <out>/pack, a datapack whose skeinprobe:setup sets game rules and force-loads
the area, and <out>/commands.txt, the placements. Those go to the server console one
line at a time: a function is checked as a whole when it loads, so a single id that
is not registered would throw away every placement in it.
"""
import io, json, os, re, sys, zipfile

mods, out = sys.argv[1], sys.argv[2]
fmt = int(sys.argv[3]) if len(sys.argv) > 3 else 48
SKIP = {'minecraft', 'neoforge', 'forge', 'c', 'fabric', 'realms'}
blocks, entities = set(), set()


def scan(z):
    for name in z.namelist():
        m = re.match(r'assets/([a-z0-9_.-]+)/blockstates/([a-z0-9_/.-]+)\.json$', name)
        if m and m.group(1) not in SKIP:
            blocks.add(f'{m.group(1)}:{m.group(2)}')
        m = re.match(r'assets/([a-z0-9_.-]+)/lang/en_us\.json$', name)
        if m and m.group(1) not in SKIP:
            try:
                lang = json.loads(z.read(name).decode('utf-8', 'replace'))
            except Exception:
                continue
            for key in lang:
                k = re.match(r'entity\.([a-z0-9_.-]+)\.([a-z0-9_/.-]+)$', key)
                if k and k.group(1) not in SKIP:
                    entities.add(f'{k.group(1)}:{k.group(2)}')
        if name.startswith('META-INF/jarjar/') and name.endswith('.jar'):
            try:
                scan(zipfile.ZipFile(io.BytesIO(z.read(name))))
            except Exception:
                pass


for jar in sorted(os.listdir(mods)):
    if jar.endswith('.jar') and not jar.startswith('skein-'):
        try:
            scan(zipfile.ZipFile(os.path.join(mods, jar)))
        except Exception:
            pass

blocks, entities = sorted(blocks), sorted(entities)
fn = os.path.join(out, 'pack', 'data', 'skeinprobe', 'function')
os.makedirs(fn, exist_ok=True)
with open(os.path.join(out, 'pack', 'pack.mcmeta'), 'w') as f:
    json.dump({'pack': {'pack_format': fmt, 'description': 'Skein compatibility probe'}}, f)

# Blocks two apart in rows of 100 from (16, 16); entities four apart below them, two of each.
ROW = 100
lines_b = [f'setblock {16 + (i % ROW) * 2} -60 {16 + (i // ROW) * 2} {b}' for i, b in enumerate(blocks)]
ez = 16 + ((len(blocks) + ROW - 1) // ROW) * 2 + 8
lines_e = []
for i, e in enumerate(entities):
    x, z = 16 + (i % 50) * 4, ez + (i // 50) * 4
    for k in range(2):
        lines_e.append(f'summon {e} {x + k + .5} -59 {z + .5}')
max_x = 16 + ROW * 2 + 16
max_z = ez + ((len(entities) + 49) // 50) * 4 + 16
setup = ['gamerule mobGriefing false', 'gamerule doDaylightCycle false', 'time set noon']
# /forceload add takes at most 256 chunks per command: tile the area 16 x 16 chunks.
for tx in range(-48, max_x + 48, 256):
    for tz in range(-48, max_z + 48, 256):
        setup.append(f'forceload add {tx} {tz} {min(tx + 255, max_x + 48)} {min(tz + 255, max_z + 48)}')
with open(os.path.join(fn, 'setup.mcfunction'), 'w') as f:
    f.write('\n'.join(setup) + '\n')
with open(os.path.join(out, 'commands.txt'), 'w') as f:
    f.write('\n'.join(lines_b + lines_e) + '\n')
print(f'{len(blocks)} blocks, {len(entities)} entities, area x {max_x} z {max_z}')
