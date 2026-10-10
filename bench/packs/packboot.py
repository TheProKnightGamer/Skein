"""Boot a pack server, moving aside client-only mods until it starts.

Usage: python3 -I bench/packs/packboot.py <server dir> <java> <args file> [max attempts] [--drop-failing]

Each attempt starts the server, waits for "Done (" or a crash report, and on a
mod-loading crash moves every jar whose failure says it needs the client, or that
depends on a jar already moved, into <server dir>/removed/, appending the name to
removed.txt. --drop-failing also moves aside mods that fail to load for any other
reason: use it only on the server without Skein, then drop the same list from the
Skein server, so the two always run the same mods. Any other failure stops the loop.
The server runs at nice 10.
"""
import glob, os, re, shutil, subprocess, sys, time

srv, java, argfile = sys.argv[1], sys.argv[2], sys.argv[3]
attempts = int(sys.argv[4]) if len(sys.argv) > 4 and sys.argv[4].isdigit() else 40
DROP_FAILING = '--drop-failing' in sys.argv
removed_dir = os.path.join(srv, 'removed')
os.makedirs(removed_dir, exist_ok=True)
CLIENT = re.compile(r'invalid dist DEDICATED_SERVER|net/minecraft/client/|net\.minecraft\.client\.|'
                    r'com/mojang/blaze3d|only (be )?(loaded|run) on (the )?client|client[- ]only|'
                    r'NoClassDefFoundError: net/minecraft/client', re.I)


def start():
    for d in ('crash-reports', 'logs'):
        shutil.rmtree(os.path.join(srv, d), ignore_errors=True)
    log = open(os.path.join(srv, 'console.log'), 'w')
    return subprocess.Popen(['nice', '-n', '10', java, '-Xmx6G', '@user_jvm_args.txt', '@' + argfile, 'nogui'],
                            cwd=srv, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT,
                            start_new_session=True)


for attempt in range(1, attempts + 1):
    p = start()
    t0 = time.time()
    result = None
    while time.time() - t0 < 900:
        time.sleep(3)
        con = open(os.path.join(srv, 'console.log'), errors='replace').read()
        if 'Done (' in con:
            result = 'done'
            break
        if glob.glob(os.path.join(srv, 'crash-reports', '*.txt')) or p.poll() is not None:
            time.sleep(3)
            result = 'crash'
            break
    if result == 'done':
        p.stdin.write(b'stop\n')
        p.stdin.flush()
        try:
            p.wait(120)
        except subprocess.TimeoutExpired:
            os.killpg(p.pid, 9)
        print(f'attempt {attempt}: started in {time.time() - t0:.0f}s')
        sys.exit(0)
    if p.poll() is None:
        os.killpg(p.pid, 9)
        p.wait()
    reports = sorted(glob.glob(os.path.join(srv, 'crash-reports', '*.txt')), key=os.path.getmtime)
    text = open(reports[-1] if reports else os.path.join(srv, 'console.log'), errors='replace').read()
    moved = []
    # NeoForge: "-- Mod loading issue for: id --" and "Mod file:"; Forge 1.20.1: "-- MOD id --" and "Mod File:".
    for chunk in re.split(r'-- (?:Mod loading issue(?: for: [^-]+)?|MOD \S+) --', text)[1:]:
        m = re.search(r'Mod [Ff]ile: (\S+\.jar)', chunk)
        jar = os.path.basename(m.group(1)) if m else None
        if not jar or not os.path.exists(os.path.join(srv, 'mods', jar)):
            continue
        if DROP_FAILING or CLIENT.search(chunk) or re.search(r'requires|depends on|is missing', chunk, re.I):
            shutil.move(os.path.join(srv, 'mods', jar), os.path.join(removed_dir, jar))
            moved.append(jar)
    if not moved:
        head = re.search(r'Description: (.*)', text)
        print(f'attempt {attempt}: failed, nothing to move aside. {head.group(1) if head else ""}')
        print('\n'.join(l for l in text.splitlines()
                        if re.search(r'Exception|Caused by|Mod file|Failure message|Suspected', l))[:3000])
        sys.exit(1)
    with open(os.path.join(srv, 'removed.txt'), 'a') as f:
        for jar in moved:
            f.write(jar + '\n')
    print(f'attempt {attempt}: moved aside {", ".join(moved)}')
print('gave up')
sys.exit(2)
