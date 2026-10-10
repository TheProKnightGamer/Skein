#!/usr/bin/env python3
"""One measurement leg: start one server, build one scenario, measure it, stop it.

    python3 bench/leg.py --target 1.21.1-neoforge --scenario entities --config B --jar <skein.jar> --out leg.json

Config A runs the server with nothing in mods/. Config B runs it with the Skein jar plus
config/skein-common.toml setting threadCount = 8 (every other key default). run.py starts each leg at
nice 10 (under a `box-run --heavy` lock when that wrapper exists); run by hand, do the same.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shlex
import shutil
import signal
import socket
import statistics
import subprocess
import sys
import threading
import time
from pathlib import Path

BENCH = Path(__file__).resolve().parent
MOD_DEV = BENCH.parent.parent
sys.path.insert(0, str(BENCH))

import scenarios  # noqa: E402
from mcbot import connect_all  # noqa: E402
from rcon import Rcon  # noqa: E402
from targets import TARGETS, java_for  # noqa: E402

HOST, PORT, RCON_PORT, PASSWORD = "127.0.0.1", 25711, 25811, "skeinbench"
HEAP_MIN, HEAP_MAX = "2G", "4G"
JVM_FLAGS = ["-XX:+UseG1GC", "-XX:MaxGCPauseMillis=100", "-Dlog4j2.formatMsgNoLookups=true"]
WARMUP_S, SAMPLES, INTERVAL_S = 60, 12, 5
LEG_DEADLINE_S = 14 * 60
BOT_NAMES = [f"Bench{i}" for i in range(1, 10)]
SKEIN_CONFIG = "# written by skein/bench/leg.py: defaults, except a fixed worker count\n[general]\n\tthreadCount = 8\n"

FLAT = ('{"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},'
        '{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}')
SERVER_PROPERTIES = f"""# written by skein/bench/leg.py
server-port={PORT}
server-ip=127.0.0.1
enable-rcon=true
rcon.port={RCON_PORT}
rcon.password={PASSWORD}
broadcast-rcon-to-ops=false
online-mode=false
enforce-secure-profile=false
prevent-proxy-connections=false
level-name=world
level-seed=skein-bench
level-type=minecraft:flat
generator-settings={FLAT}
generate-structures=false
difficulty=easy
spawn-protection=0
view-distance=4
simulation-distance=4
max-players=20
pause-when-empty-seconds=0
max-tick-time=-1
sync-chunk-writes=false
network-compression-threshold=256
allow-flight=true
white-list=false
motd=skein bench
"""

_TPS_LINE = re.compile(r"^(?P<label>.*?):\s+(?:Mean tick time: (?P<a>[\d.,]+) ms|[\d.,]+ TPS \((?P<b>[\d.,]+) ms/tick\))")


def sanitize(s: str) -> str:
    s = s.replace(str(MOD_DEV) + "/", "mod_dev/").replace(str(MOD_DEV), "mod_dev")
    s = s.replace(str(Path.home()), "~")
    # Any other user's home directory too (paths in other processes' command lines).
    return re.sub(r"/" + "home" + r"/[^/\s]+", "~", s)


def rel(p: Path | str) -> str:
    return sanitize(str(Path(p).resolve()))


def dim_key(label: str) -> str:
    l = label.lower()
    if l.startswith("overall"):
        return "overall"
    for key in ("overworld", "nether", "end"):
        if key in l:
            return {"nether": "the_nether", "end": "the_end"}.get(key, key)
    return l.strip()


def parse_tps(text: str) -> dict[str, float]:
    out = {}
    for line in text.splitlines():
        m = _TPS_LINE.match(line.strip())
        if m:
            out[dim_key(m.group("label"))] = float((m.group("a") or m.group("b")).replace(",", "."))
    return out


def mem_available_gb() -> float:
    for line in Path("/proc/meminfo").read_text().splitlines():
        if line.startswith("MemAvailable:"):
            return int(line.split()[1]) / 1024 / 1024
    return 0.0


def other_jvms(own: set[int]) -> list[dict]:
    """Every other java process on the box, so a noisy leg can be explained later."""
    found = []
    for d in Path("/proc").iterdir():
        if not d.name.isdigit() or int(d.name) in own:
            continue
        try:
            argv = (d / "cmdline").read_bytes().split(b"\0")
            if not argv or not argv[0].endswith(b"java"):
                continue
            args = [a.decode(errors="replace") for a in argv if a]
            rss = 0
            for line in (d / "status").read_text().splitlines():
                if line.startswith("VmRSS:"):
                    rss = int(line.split()[1]) // 1024
            what = next((a for a in args if a.endswith("GradleDaemon") or a.endswith("GradleWorkerMain")), None)
            if what is None and "-jar" in args:
                what = Path(args[args.index("-jar") + 1]).name
            if what is None:
                what = next((a for a in reversed(args) if re.match(r"^[a-z][\w.]*\.[A-Z]\w+$", a)), args[-1])
            try:
                cwd = Path(os.readlink(d / "cwd")).name
            except OSError:
                cwd = "?"
            found.append({"pid": int(d.name), "rss_mb": rss, "what": sanitize(what)[:80], "cwd": sanitize(cwd)})
        except (OSError, ValueError):
            continue
    return sorted(found, key=lambda j: -j["rss_mb"])


def launch_argv(srv: Path, java: str) -> list[str]:
    """The installer's own run.sh line, with our JVM, heap and flags."""
    lines = [l.strip() for l in (srv / "run.sh").read_text().splitlines()
             if re.match(r"^(exec\s+)?java\s", l.strip()) and "--onlyCheckJava" not in l]
    if not lines:
        raise SystemExit(f"no java line in {srv}/run.sh")
    tokens = shlex.split(lines[-1])
    tokens = tokens[tokens.index("java") + 1:]
    argv = [java, f"-Xms{HEAP_MIN}", f"-Xmx{HEAP_MAX}", *JVM_FLAGS]
    for a in tokens:
        if a in ("@user_jvm_args.txt", "$@", "||", "exit", "1"):
            continue
        argv.append(a)
    return argv + ["nogui"]


def can_freeze(mc: str) -> bool:
    """Whether this version has /tick freeze (added in 1.20.3; 26.x and later have it too)."""
    parts = [int(x) for x in re.findall(r"\d+", mc)[:3]] + [0, 0, 0]
    return parts[0] > 1 or (parts[0] == 1 and (parts[1], parts[2]) >= (20, 3))


def port_busy(port: int) -> bool:
    with socket.socket() as s:
        s.settimeout(0.5)
        return s.connect_ex((HOST, port)) == 0


def reset(srv: Path, config: str, jar: Path | None, extra_mods: list[Path] = ()) -> None:
    for name in ("world", "logs", "crash-reports", "config", "mods", "usercache.json", "ops.json",
                 "banned-players.json", "banned-ips.json", "whitelist.json", "debug"):
        p = srv / name
        if p.is_dir():
            shutil.rmtree(p)
        elif p.exists():
            p.unlink()
    (srv / "mods").mkdir()
    # Other mods run in both configs, so A and B still differ only by Skein.
    for extra in extra_mods:
        shutil.copy2(extra, srv / "mods" / extra.name)
    (srv / "eula.txt").write_text("eula=true\n")
    (srv / "server.properties").write_text(SERVER_PROPERTIES)
    if config == "B":
        shutil.copy2(jar, srv / "mods" / jar.name)
        (srv / "config").mkdir(exist_ok=True)
        (srv / "config" / "skein-common.toml").write_text(SKEIN_CONFIG)


def scan_log(srv: Path) -> dict:
    log = srv / "logs" / "latest.log"
    if not log.exists():
        return {"missing": True}
    errors, cant_keep_up, skein_warn, samples, paused = 0, 0, 0, [], False
    for line in log.read_text(errors="replace").splitlines():
        if "Server empty for" in line:
            paused = True  # 1.21.2+ stops ticking with no players; the samples would be of an idle server
        if "Can't keep up" in line:
            cant_keep_up += 1
        if re.search(r"/(ERROR|FATAL)\]", line) or "Exception" in line:
            errors += 1
            if len(samples) < 12:
                samples.append(sanitize(line)[:300])
        if "/WARN]" in line and "skein" in line.lower():
            skein_warn += 1
            if len(samples) < 12:
                samples.append(sanitize(line)[:300])
    return {"error_lines": errors, "cant_keep_up": cant_keep_up, "skein_warn_lines": skein_warn,
            "paused_when_empty": paused, "samples": samples}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--target", required=True, choices=sorted(TARGETS))
    ap.add_argument("--scenario", required=True, choices=scenarios.SCENARIOS)
    ap.add_argument("--config", required=True, choices=["A", "B"])
    ap.add_argument("--jar", help="Skein jar (config B)")
    ap.add_argument("--rep", type=int, default=1)
    ap.add_argument("--out", required=True)
    ap.add_argument("--warmup", type=int, default=WARMUP_S)
    ap.add_argument("--samples", type=int, default=SAMPLES)
    ap.add_argument("--extra-mod", action="append", default=[], type=Path,
                    help="another mod jar to install in both configs (repeatable)")
    ap.add_argument("--jfr", type=Path, help="record a Flight Recorder profile of the sampled window to this file")
    args = ap.parse_args()

    t = TARGETS[args.target]
    srv = BENCH / "servers" / t.name
    if not (srv / "run.sh").exists():
        print(f"server for {t.name} not installed; run: python3 bench/run.py --provision-only --target {t.name}")
        return 2
    jar = Path(args.jar).resolve() if args.jar else None
    if args.config == "B" and (jar is None or not jar.is_file()):
        print("config B needs --jar")
        return 2
    for p in (PORT, RCON_PORT):
        if port_busy(p):
            print(f"port {p} is in use; refusing to start")
            return 3

    t_start = time.monotonic()
    result: dict = {
        "target": t.name, "scenario": args.scenario, "config": args.config, "rep": args.rep,
        "jar": rel(jar) if (jar and args.config == "B") else None,
        "jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest() if (jar and args.config == "B") else None,
        "started": time.strftime("%Y-%m-%d %H:%M:%S"), "ok": False,
        "extra_mods": [{"jar": e.name, "sha256": hashlib.sha256(e.read_bytes()).hexdigest()} for e in args.extra_mod],
    }
    reset(srv, args.config, jar, args.extra_mod)
    java = java_for(t.java)
    argv = launch_argv(srv, java)
    stdout = open(srv / "bench-console.log", "w")
    proc = subprocess.Popen(argv, cwd=srv, stdin=subprocess.PIPE, stdout=stdout, stderr=subprocess.STDOUT,
                            start_new_session=True)
    result["server_pid"] = proc.pid

    def kill_hard():
        if proc.poll() is None:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass

    watchdog = threading.Timer(LEG_DEADLINE_S, lambda: (print("leg deadline hit; killing server", flush=True), kill_hard()))
    watchdog.daemon = True
    watchdog.start()

    rcon, bots = None, []
    try:
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                raise RuntimeError(f"server exited during startup with code {proc.returncode}")
            if "Done (" in (srv / "bench-console.log").read_text(errors="replace"):
                break
            time.sleep(1.0)
        else:
            raise RuntimeError("server did not finish starting in 300 s")
        result["startup_s"] = round(time.monotonic() - t_start, 1)
        rcon = Rcon(HOST, RCON_PORT, PASSWORD).connect(time.monotonic() + 60)

        w = scenarios.World(rcon, t, print)
        layout = scenarios.build_layout(args.scenario)
        bots = connect_all(BOT_NAMES, HOST, PORT, t.proto)
        for name, (x, z) in zip(BOT_NAMES, layout):
            w.cmd(f"gamemode creative {name}")
            w.cmd(f"tp {name} {x}.5 0 {z}.5")
        time.sleep(3.0)
        built = scenarios.build(args.scenario, w)
        result["setup"] = {"seconds": built.seconds, "commands": built.commands, "forced_chunks": built.forced_chunks,
                           "random_tick_speed": built.random_tick_speed, "hoppers": len(built.hoppers),
                           "bots": len(bots)}
        counts_before = {tag: w.count(f"@e[tag={tag}]") for tag in built.expect_entities}

        # warm up, then sample the loader's mean tick time (a 100-tick window) every INTERVAL_S seconds
        time.sleep(args.warmup)
        dropped = [b.name + ": " + str(b.disconnect_reason) for b in bots if not b.online]
        if dropped:
            raise RuntimeError(f"players dropped before measuring: {dropped}")
        result["loadavg_before"] = [round(x, 2) for x in os.getloadavg()]
        result["mem_available_gb"] = round(mem_available_gb(), 1)
        result["other_jvms"] = other_jvms({proc.pid, os.getpid()})
        jcmd = str(Path(java).with_name("jcmd"))
        if args.jfr:
            args.jfr = args.jfr.resolve()
            subprocess.run([jcmd, str(proc.pid), "JFR.start", "name=skeinbench", "settings=profile",
                            f"filename={args.jfr}"], check=True, capture_output=True, timeout=60)
        samples = []
        next_at = time.monotonic()
        for _ in range(args.samples):
            next_at += INTERVAL_S
            time.sleep(max(0.0, next_at - time.monotonic()))
            text = rcon.command(t.tps_command)
            parsed = parse_tps(text)
            if "overall" not in parsed:
                raise RuntimeError(f"could not parse /{t.tps_command}: {text[:300]!r}")
            samples.append(parsed)
        result["loadavg_after"] = [round(x, 2) for x in os.getloadavg()]
        if args.jfr:
            subprocess.run([jcmd, str(proc.pid), "JFR.stop", "name=skeinbench"], check=True, capture_output=True,
                           timeout=120)
            result["jfr"] = str(args.jfr)
        result["samples"] = samples
        overall = [s["overall"] for s in samples]
        result["median_ms"] = round(statistics.median(overall), 3)
        result["dims_median_ms"] = {k: round(statistics.median([s[k] for s in samples if k in s]), 3)
                                    for k in samples[0] if k != "overall"}

        # Skein's own figures, while the game still runs: /tick freeze below would leave them
        # describing a frozen level
        if args.config == "B":
            result["skein_status"] = rcon.command("skein status")
            m = re.search(r"hooks: (\d+) hooks checked, (\d+) missing", result["skein_status"])
            result["skein_hooks"] = {"checked": int(m.group(1)), "missing": int(m.group(2))} if m else None

        # correctness. The hoppers are read one command at a time while they pass items on, so
        # an item in transit could be counted twice or not at all; where /tick freeze exists
        # (1.20.3+) the count is taken with the game stopped.
        frozen = can_freeze(t.mc)
        if frozen:
            rcon.command("tick freeze")
            time.sleep(0.5)
        counts_after = {tag: w.count(f"@e[tag={tag}]") for tag in built.expect_entities}
        items = sum(w.items_in(*h) for h in built.hoppers)
        if frozen:
            rcon.command("tick unfreeze")
        online = sum(1 for b in bots if b.online)
        listing = rcon.command("list")
        checks = {
            "entities_expected": built.expect_entities, "entities_before": counts_before, "entities_after": counts_after,
            "items_expected": built.expect_items, "items_after": items, "counted_frozen": frozen,
            "bots_online_after": online, "list": listing.strip()[:200],
        }
        checks["pass"] = (counts_after == built.expect_entities == counts_before and items == built.expect_items
                          and online == len(BOT_NAMES))
        result["checks"] = checks
        result["ok"] = True
    except Exception as e:  # noqa: BLE001
        result["error"] = f"{type(e).__name__}: {sanitize(str(e))}"
        print("leg failed:", result["error"], flush=True)
    finally:
        for b in bots:
            b.close()
        result["bot_disconnects"] = [b.disconnect_reason for b in bots if b.disconnect_reason]
        if rcon is not None and proc.poll() is None:
            try:
                rcon.command("stop")
            except Exception:  # noqa: BLE001
                pass
            rcon.close()
        try:
            proc.wait(timeout=120)
        except subprocess.TimeoutExpired:
            kill_hard()
            proc.wait(timeout=30)
            result["stop"] = "SIGKILL after 120 s"
        watchdog.cancel()
        stdout.close()
        result["log"] = scan_log(srv)
        if result["log"].get("paused_when_empty"):
            result["ok"] = False
            result["error"] = "server logged 'Server empty for ... pausing': the leg measured an idle server"
        result["leg_seconds"] = round(time.monotonic() - t_start, 1)
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(json.dumps(result, indent=1))
        print(f"{t.name} {args.scenario} {args.config}{args.rep}: "
              f"{result.get('median_ms')} ms/tick ok={result['ok']} checks={result.get('checks', {}).get('pass')} "
              f"({result['leg_seconds']} s)", flush=True)
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
