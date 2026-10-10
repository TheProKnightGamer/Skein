"""Run the compatibility probe on a pack server and summarise what went wrong.

Usage: python3 -I bench/packs/runprobe.py <server dir> <probe datapack> <java> <args file> [seconds]

Recreates the world with the probe datapack, starts the server at nice 10, force-loads
the probe area, sends every placement in commands.txt to the console, lets it tick, asks /skein status
when Skein is installed, stops, and writes <server dir>/probe.txt and probe.json.
"""
import collections, json, os, re, shutil, subprocess, sys, time

srv, probe, java, argfile = sys.argv[1:5]
seconds = int(sys.argv[5]) if len(sys.argv) > 5 else 180
for d in ('world', 'logs', 'crash-reports'):
    shutil.rmtree(os.path.join(srv, d), ignore_errors=True)
os.makedirs(os.path.join(srv, 'world', 'datapacks'))
shutil.copytree(os.path.join(probe, 'pack'), os.path.join(srv, 'world', 'datapacks', 'skeinprobe'))
skein = any(n.startswith('skein-') for n in os.listdir(os.path.join(srv, 'mods')))

log = open(os.path.join(srv, 'console.log'), 'w')
p = subprocess.Popen(['nice', '-n', '10', java, '-Xmx6G', '@user_jvm_args.txt', '@' + argfile, 'nogui'], cwd=srv,
                     stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)


def crashed():
    """A crash report, or a server that has exited. A crashed server can stay alive with
    its console no longer reading, and writing to it would then block for good."""
    return p.poll() is not None or bool(os.listdir(os.path.join(srv, 'crash-reports'))
                                        if os.path.isdir(os.path.join(srv, 'crash-reports')) else False)


def say(cmd):
    send(cmd + '\n')


def send(text):
    if crashed():
        return
    try:
        p.stdin.write(text.encode())
        p.stdin.flush()
    except OSError:
        pass


def console():
    return open(os.path.join(srv, 'console.log'), errors='replace').read()


t0 = time.time()
while 'Done (' not in console():
    if p.poll() is not None or time.time() - t0 > 900:
        print('server did not start')
        sys.exit(1)
    time.sleep(3)
say('function skeinprobe:setup')
time.sleep(30)
# One command per console line, so an id that is not registered fails on its own.
commands = open(os.path.join(probe, 'commands.txt')).read().split('\n')
for n in range(0, len(commands), 500):
    send('\n'.join(c for c in commands[n:n + 500] if c) + '\n')
    time.sleep(1)
for _ in range(seconds):
    if crashed():
        break
    time.sleep(1)
if skein:
    say('skein status')
    time.sleep(5)
alive = not crashed()
say('stop')
try:
    p.wait(20 if not alive else 180)
except subprocess.TimeoutExpired:
    os.killpg(p.pid, 9)

path = os.path.join(srv, 'logs', 'latest.log')
lines = open(path if os.path.exists(path) else os.path.join(srv, 'console.log'), errors='replace').read().splitlines()
errors = collections.Counter()
for line in lines:
    if re.search(r'/(ERROR|FATAL)\]', line) or re.match(r'\S*(Exception|Error)(:|$)', line.strip()):
        msg = re.sub(r'^\[[^\]]*\] \[[^\]]*\] \[[^\]]*\]: ', '', line).strip()
        errors[re.sub(r'\d+(\.\d+)?', 'N', msg)[:200]] += 1
skein_lines = [l for l in lines if 'com.theproknightgamer.skein' in l and re.search(r'WARN|ERROR', l)]
status = []
for i, line in enumerate(lines):
    if line.rstrip().endswith(': Skein'):
        status = lines[i:i + 20]
behind = sum(1 for l in lines if "Can't keep up" in l)
summary = {'alive': alive, 'seconds': seconds, 'cant_keep_up': behind, 'errors': dict(errors),
           'skein_lines': skein_lines[:200], 'status': status}
with open(os.path.join(srv, 'probe.json'), 'w') as f:
    json.dump(summary, f, indent=1)
with open(os.path.join(srv, 'probe.txt'), 'w') as f:
    f.write(f'server alive at the end: {alive}; ticked {seconds}s after the fill\n')
    f.write(f"can't keep up: {behind}\n")
    f.write(f'distinct error lines: {len(errors)}, total {sum(errors.values())}\n')
    for msg, n in errors.most_common(60):
        f.write(f'  {n:6} {msg}\n')
    f.write(f'skein warnings/errors: {len(skein_lines)}\n')
    for line in skein_lines[:40]:
        f.write('  ' + line[:400] + '\n')
    f.write('skein status:\n' + '\n'.join('  ' + line[:300] for line in status) + '\n')
print(open(os.path.join(srv, 'probe.txt')).read()[:4000])
