"""Benchmark scenarios, built over RCON from vanilla commands only.

Every scenario starts from the same freshly generated superflat world (fixed seed, explicit layers:
bedrock y=-64, dirt y=-63..-62, grass y=-61, so the surface is y=-60). Build order and every
coordinate are deterministic, so runs A and B differ only in the jar.

    entities  1440 animals (cow/sheep/pig/villager) in 144 walled pens over 24x24 chunks
              (32x32 force-loaded)
    hoppers   576 hoppers as 144 closed 4-hopper rings over 24x24 chunks (32x32 force-loaded),
              9216 items circulating; conservation checked
    random    randomTickSpeed 80 over 32x32 chunks (40x40 force-loaded) of moist farmland (each
              tick scans 9x2x9 for water) with water channels every 9 blocks
    mixed     all three (farm 32x32, pens and rings 24x24, 40x40 force-loaded), plus 480 animals on
              a platform in the nether (8x8 chunks, 16x16 force-loaded) so two levels are busy
    quiet     no force-loading, randomTickSpeed 3, around each of 9 players: 10 animals, 3 hopper
              rings, a 16x16 farm. The regression guard: a normal server with nothing to parallelise.
"""
from __future__ import annotations

import re
import time
from dataclasses import dataclass, field

from targets import Target, rule_values

Y_SURF = -60          # first air block above the grass
Y_TOP = -61           # the grass layer
Y_HOPPERS = -50       # hopper rings float 10 blocks up, clear of pens and farms
NETHER_Y = 200        # nether platform (above the bedrock roof, below the build limit)

AREA_24 = (-192, -192, 191, 191)
AREA_32 = (-256, -256, 255, 255)
AREA_40 = (-320, -320, 319, 319)
NETHER_AREA = (-64, -64, 63, 63)
NETHER_FORCED = (-128, -128, 127, 127)
# Content never sits on the edge of the loaded world. Every busy area has a 4-chunk ring of loaded,
# empty chunks around it, as a farm inside a player's view would. Without the ring, the outer 3
# chunks fall inside Skein's entities.loadedMarginChunks and are pinned serial (measured: 346 of
# 1476 entities) -- an artefact of force-loading, not of the workload.

MOB_MIX = ["cow", "cow", "cow", "cow", "sheep", "sheep", "sheep", "pig", "pig", "villager"]
NETHER_MIX = ["cow", "pig", "sheep"]
RING_ITEMS = ["minecraft:cobblestone", "minecraft:dirt", "minecraft:oak_planks", "minecraft:stone"]
ITEMS_PER_HOPPER = 16

_ERR = re.compile(r"Unknown or incomplete command|Incorrect argument|Expected |Invalid |Unknown (block|item|entity|"
                  r"game ?rule|dimension)|not loaded|Too many|Error executing|<--\[HERE\]|Unable to|"
                  r"Can't|Cannot", re.I)
_COUNT = re.compile(r"count: ?(\d+)", re.I)


class CommandFailed(RuntimeError):
    pass


@dataclass
class Built:
    scenario: str
    bots: list[tuple[int, int]]
    expect_entities: dict[str, int] = field(default_factory=dict)    # tag -> count
    hoppers: list[tuple[int, int, int]] = field(default_factory=list)
    expect_items: int = 0
    random_tick_speed: int = 3
    forced_chunks: int = 0
    commands: int = 0
    seconds: float = 0.0


class World:
    def __init__(self, rcon, target: Target, log):
        self.rcon, self.t, self.log = rcon, target, log
        self.commands = 0

    def cmd(self, c: str, retry_unloaded: float = 0.0, ok_if: str | None = None) -> str:
        deadline = time.monotonic() + retry_unloaded
        while True:
            out = self.rcon.command(c)
            self.commands += 1
            if ok_if and re.search(ok_if, out, re.I):
                return out
            if _ERR.search(out):
                if "not loaded" in out and time.monotonic() < deadline:
                    time.sleep(0.5)
                    continue
                raise CommandFailed(f"/{c} -> {out.strip()[:300]}")
            return out

    def rule(self, logical: str, value: str) -> None:
        self.cmd(f"gamerule {self.t.dialect.rules[logical]} {value}")

    def random_tick_speed(self, n: int) -> None:
        self.cmd(f"gamerule {self.t.dialect.random_tick_rule} {n}")

    def count(self, selector: str, dim: str | None = None) -> int:
        pre = f"execute in {dim} " if dim else "execute "
        out = self.rcon.command(f"{pre}if entity {selector}")
        m = re.search(r"count: ?(\d+)", out, re.I)  # "Test passed, count: N" / 26.2 "Test passed. Count: N"
        if m:
            return int(m.group(1))
        if "failed" in out.lower():
            return 0
        raise CommandFailed(f"count {selector} -> {out.strip()[:200]}")

    def items_in(self, x: int, y: int, z: int) -> int:
        out = self.rcon.command(f"data get block {x} {y} {z} Items")
        return sum(int(n) for n in _COUNT.findall(out))

    # --- building blocks -------------------------------------------------------------------------
    def forceload(self, area, dim: str = "minecraft:overworld") -> int:
        x1, z1, x2, z2 = area
        cx1, cz1, cx2, cz2 = x1 >> 4, z1 >> 4, x2 >> 4, z2 >> 4
        n = 0
        for a in range(cx1, cx2 + 1, 16):
            for b in range(cz1, cz2 + 1, 16):
                ea, eb = min(a + 15, cx2), min(b + 15, cz2)
                self.cmd(f"execute in {dim} run forceload add {a * 16} {b * 16} {ea * 16 + 15} {eb * 16 + 15}")
                n += (ea - a + 1) * (eb - b + 1)
        return n

    def wait_loaded(self, area, dim: str = "minecraft:overworld", timeout: float = 150.0) -> None:
        x1, z1, x2, z2 = area
        pending = [(cx * 16 + 8, cz * 16 + 8) for cx in range(x1 >> 4, (x2 >> 4) + 1)
                   for cz in range(z1 >> 4, (z2 >> 4) + 1)]
        deadline = time.monotonic() + timeout
        while pending:
            still = []
            for x, z in pending:
                out = self.rcon.command(f"execute in {dim} if loaded {x} {Y_SURF} {z}")
                if "passed" not in out.lower():
                    still.append((x, z))
            pending = still
            if pending:
                if time.monotonic() > deadline:
                    raise CommandFailed(f"{len(pending)} chunks in {dim} never loaded")
                time.sleep(1.0)

    def fill(self, x1, y1, z1, x2, y2, z2, block, dim: str | None = None) -> None:
        pre = f"execute in {dim} run " if dim else ""
        self.cmd(f"{pre}fill {x1} {y1} {z1} {x2} {y2} {z2} {block}", retry_unloaded=60,
                 ok_if=r"No blocks were filled")

    def farmland(self, area) -> None:
        """Moist farmland with a water channel every 9 blocks, so every tile has water within 4."""
        x1, z1, x2, z2 = area
        width = max(1, 32768 // (z2 - z1 + 1))
        for x in range(x1, x2 + 1, width):
            self.fill(x, Y_TOP, z1, min(x + width - 1, x2), Y_TOP, z2, "minecraft:farmland[moisture=7]")
        for x in range(x1 + 4, x2 + 1, 9):
            self.fill(x, Y_TOP, z1, x, Y_TOP, z2, "minecraft:water")

    def pens(self, area, per_pen: list[str], tag: str, pen: int = 32) -> int:
        """Two-high glass walls on a pen grid, and per_pen animals in every pen.

        The walls also replace the ground block under them. Otherwise a farm's water channel runs on
        under a wall as a one-block tunnel, and a pig (0.9 tall) swims in and drowns under the glass.
        """
        x1, z1, x2, z2 = area
        lines = list(range(x1, x2 + 1, pen)) + [x2]
        for x in lines:
            self.fill(x, Y_TOP, z1, x, Y_SURF + 1, z2, "minecraft:glass")
        for z in list(range(z1, z2 + 1, pen)) + [z2]:
            self.fill(x1, Y_TOP, z, x2, Y_SURF + 1, z, "minecraft:glass")
        n = 0
        for px in range(x1, x2 + 1 - pen + 1, pen):
            for pz in range(z1, z2 + 1 - pen + 1, pen):
                for k, mob in enumerate(per_pen):
                    x = px + 2 + (k * 7) % (pen - 3)
                    z = pz + 2 + (k * 11) % (pen - 3)
                    self.cmd(f'summon minecraft:{mob} {x}.5 {Y_SURF} {z}.5 {{Tags:["{tag}"]}}')
                    n += 1
        return n

    def ring(self, x: int, y: int, z: int) -> list[tuple[int, int, int]]:
        """Four hoppers passing items round a 2x2 loop: E -> S -> W -> N -> back."""
        spots = [(x, z, "east"), (x + 1, z, "south"), (x + 1, z + 1, "west"), (x, z + 1, "north")]
        out = []
        for i, (hx, hz, facing) in enumerate(spots):
            item = self.t.dialect.item_nbt.format(slot=0, id=RING_ITEMS[i], n=ITEMS_PER_HOPPER)
            self.cmd(f"setblock {hx} {y} {hz} minecraft:hopper[facing={facing}]{{Items:[{item}]}}", retry_unloaded=60)
            out.append((hx, y, hz))
        return out


# --- bot layouts ---------------------------------------------------------------------------------
def grid3(step: int) -> list[tuple[int, int]]:
    return [(x, z) for x in (-step, 0, step) for z in (-step, 0, step)]


BOTS_24 = grid3(128)   # every chunk of AREA_24 within 128 blocks of a player
BOTS_32 = grid3(171)   # every chunk of AREA_32 within 128 blocks of a player


def build_layout(name: str) -> list[tuple[int, int]]:
    """Where the nine players stand (at y=0, far above the build) for a scenario."""
    return BOTS_24 if name in ("entities", "hoppers") else BOTS_32


def base(w: World, rts: int) -> None:
    for logical, value in rule_values(w.t.mc).items():
        w.rule(logical, value)
    w.random_tick_speed(rts)
    w.cmd("time set 6000")
    w.cmd("weather clear")
    w.cmd(f"setworldspawn 0 {Y_SURF} 0")


def build(name: str, w: World) -> Built:
    t0 = time.monotonic()
    if name == "entities":
        b = Built(name, BOTS_24)
        base(w, 3)
        b.forced_chunks = w.forceload(AREA_32)
        w.wait_loaded(AREA_32)
        b.expect_entities["bench"] = w.pens(AREA_24, MOB_MIX, "bench")
    elif name == "hoppers":
        b = Built(name, BOTS_24)
        base(w, 3)
        b.forced_chunks = w.forceload(AREA_32)
        w.wait_loaded(AREA_32)
        for x in range(AREA_24[0] + 8, AREA_24[2], 32):
            for z in range(AREA_24[1] + 8, AREA_24[3], 32):
                b.hoppers += w.ring(x, Y_HOPPERS, z)
    elif name == "random":
        b = Built(name, BOTS_32, random_tick_speed=80)
        base(w, 80)
        b.forced_chunks = w.forceload(AREA_40)
        w.wait_loaded(AREA_40)
        w.farmland(AREA_32)
    elif name == "mixed":
        b = Built(name, BOTS_32, random_tick_speed=80)
        base(w, 80)
        b.forced_chunks = w.forceload(AREA_40)
        w.wait_loaded(AREA_40)
        w.farmland(AREA_32)
        b.expect_entities["bench"] = w.pens(AREA_24, MOB_MIX, "bench")
        for x in range(AREA_24[0] + 8, AREA_24[2], 32):
            for z in range(AREA_24[1] + 8, AREA_24[3], 32):
                b.hoppers += w.ring(x, Y_HOPPERS, z)
        nether = "minecraft:the_nether"
        b.forced_chunks += w.forceload(NETHER_FORCED, nether)
        w.wait_loaded(NETHER_FORCED, nether)
        x1, z1, x2, z2 = NETHER_AREA
        w.fill(x1, NETHER_Y - 1, z1, x2, NETHER_Y - 1, z2, "minecraft:glass", nether)
        for x in (x1, x2):
            w.fill(x, NETHER_Y, z1, x, NETHER_Y + 1, z2, "minecraft:glass", nether)
        for z in (z1, z2):
            w.fill(x1, NETHER_Y, z, x2, NETHER_Y + 1, z, "minecraft:glass", nether)
        n = 0
        for i in range(480):
            x = x1 + 2 + (i * 37) % (x2 - x1 - 3)
            z = z1 + 2 + (i * 53) % (z2 - z1 - 3)
            w.cmd(f'execute in {nether} run summon minecraft:{NETHER_MIX[i % 3]} {x}.5 {NETHER_Y} {z}.5 '
                  f'{{Tags:["bench_nether"]}}')
            n += 1
        b.expect_entities["bench_nether"] = n
    elif name == "quiet":
        b = Built(name, BOTS_32)
        base(w, 3)
        # no force-loading: the players' own view areas are the world
        n = 0
        for bx, bz in BOTS_32:
            w.wait_loaded((bx - 24, bz - 24, bx + 23, bz + 23))
            n += w.pens((bx - 20, bz - 20, bx - 5, bz - 5), MOB_MIX, "bench", pen=15)
            for k in range(3):
                b.hoppers += w.ring(bx + 4 + 4 * k, Y_HOPPERS, bz + 4)
            w.farmland((bx + 2, bz - 20, bx + 17, bz - 5))
        b.expect_entities["bench"] = n
    else:
        raise SystemExit(f"unknown scenario {name}")
    b.expect_items = len(b.hoppers) * ITEMS_PER_HOPPER
    b.commands = w.commands
    b.seconds = round(time.monotonic() - t0, 1)
    return b


SCENARIOS = ["entities", "hoppers", "random", "mixed", "quiet"]
