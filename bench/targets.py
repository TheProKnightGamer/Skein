"""Every version- and loader-specific fact the harness uses, in one place.

A target is `<mc>-<loader>`, e.g. `1.21.1-neoforge`. The rest of the harness never branches on a
version string. It asks this module for the protocol table, the command dialect, the TPS command
and the JVM.

Packet ids were generated from the decompiled sources (`mod_dev/.mc-<ver>-sources`, and the
official-mapped Forge 1.20.1 sources for 1.20.1). A packet's id is its position in the protocol
template's registration order, and in the play/clientbound template the bundle delimiter comes first.
They were checked against live servers where noted in README.md.
"""
from __future__ import annotations

import glob
import os
from dataclasses import dataclass, field
from pathlib import Path


# ---------------------------------------------------------------------------------------------
# Wire protocol. Only the packets a silent, standing, creative-mode client has to answer.
# ---------------------------------------------------------------------------------------------
@dataclass(frozen=True)
class Proto:
    version: int                 # handshake protocol number (SharedConstants)
    config_phase: bool           # 1.20.2+: login -> configuration -> play
    hello_uuid_flag: bool        # 1.20.1 login hello: name, bool hasUuid, uuid. Later: name, uuid
    pos_id_first: bool           # 1.21.2+: PlayerPosition starts with the teleport id
    particle_status: bool        # 1.21.2+: ClientInformation ends with particleStatus
    # play, clientbound
    cb_disconnect: int
    cb_keepalive: int
    cb_ping: int
    cb_position: int
    cb_chunk_batch_finished: int | None
    cb_start_configuration: int | None
    # play, serverbound
    sb_accept_teleport: int
    sb_keepalive: int
    sb_pong: int
    sb_client_info: int | None   # only sent in play before 1.20.2
    sb_chunk_batch_received: int | None
    sb_player_loaded: int | None
    sb_config_ack: int | None
    sb_move_pos: int             # MovePlayer.Pos: x, y, z (doubles), flags byte (onGround etc.)


# Login ids are the same in every version here. Configuration ids are the same from 1.20.5 to 26.2
# for everything used (1.21.1 has no code-of-conduct packet, and the server never sends one).
LOGIN_CB = dict(disconnect=0x00, hello=0x01, finished=0x02, compression=0x03, custom_query=0x04, cookie=0x05)
LOGIN_SB = dict(hello=0x00, custom_query_answer=0x02, login_ack=0x03, cookie=0x04)
CONFIG_CB = dict(cookie=0x00, custom_payload=0x01, disconnect=0x02, finish=0x03, keepalive=0x04, ping=0x05,
                 resource_pack_push=0x09, known_packs=0x0E, code_of_conduct=0x13)
CONFIG_SB = dict(client_info=0x00, cookie=0x01, custom_payload=0x02, finish=0x03, keepalive=0x04, pong=0x05,
                 resource_pack=0x06, known_packs=0x07, accept_code_of_conduct=0x09)

PROTOCOLS: dict[str, Proto] = {
    # Forge 1.20.1 official sources, ConnectionProtocol.PLAY. Checked against protocol 763 docs.
    "1.20.1": Proto(763, config_phase=False, hello_uuid_flag=True, pos_id_first=False, particle_status=False,
                    cb_disconnect=0x1A, cb_keepalive=0x23, cb_ping=0x32, cb_position=0x3C,
                    cb_chunk_batch_finished=None, cb_start_configuration=None,
                    sb_accept_teleport=0x00, sb_keepalive=0x12, sb_pong=0x20, sb_client_info=0x08,
                    sb_chunk_batch_received=None, sb_player_loaded=None, sb_config_ack=None, sb_move_pos=0x14),
    "1.21.1": Proto(767, config_phase=True, hello_uuid_flag=False, pos_id_first=False, particle_status=False,
                    cb_disconnect=0x1D, cb_keepalive=0x26, cb_ping=0x35, cb_position=0x40,
                    cb_chunk_batch_finished=0x0C, cb_start_configuration=0x69,
                    sb_accept_teleport=0x00, sb_keepalive=0x18, sb_pong=0x27, sb_client_info=None,
                    sb_chunk_batch_received=0x08, sb_player_loaded=None, sb_config_ack=0x0C, sb_move_pos=0x1A),
    "1.21.11": Proto(774, config_phase=True, hello_uuid_flag=False, pos_id_first=True, particle_status=True,
                     cb_disconnect=0x20, cb_keepalive=0x2B, cb_ping=0x3B, cb_position=0x46,
                     cb_chunk_batch_finished=0x0B, cb_start_configuration=0x74,
                     sb_accept_teleport=0x00, sb_keepalive=0x1B, sb_pong=0x2C, sb_client_info=None,
                     sb_chunk_batch_received=0x0A, sb_player_loaded=0x2B, sb_config_ack=0x0F, sb_move_pos=0x1D),
    "26.2": Proto(776, config_phase=True, hello_uuid_flag=False, pos_id_first=True, particle_status=True,
                  cb_disconnect=0x20, cb_keepalive=0x2C, cb_ping=0x3D, cb_position=0x48,
                  cb_chunk_batch_finished=0x0B, cb_start_configuration=0x76,
                  sb_accept_teleport=0x00, sb_keepalive=0x1C, sb_pong=0x2D, sb_client_info=None,
                  sb_chunk_batch_received=0x0B, sb_player_loaded=0x2C, sb_config_ack=0x10, sb_move_pos=0x1E),
}


# ---------------------------------------------------------------------------------------------
# Command dialect.
# ---------------------------------------------------------------------------------------------
@dataclass(frozen=True)
class Dialect:
    item_nbt: str                           # one stack in a container's Items list; {slot} {id} {n}
    rules: dict[str, str] = field(default_factory=dict)   # logical name -> gamerule id
    random_tick_rule: str = "randomTickSpeed"


# 1.20.1 .. 1.21.10: camelCase gamerules. 1.21.11+: snake_case ids (GameRules in .mc-1.21.11-sources
# and .mc-26.2-sources list the same ids).
_OLD_RULES = {
    "spawning": "doMobSpawning", "daylight": "doDaylightCycle", "weather": "doWeatherCycle",
    "griefing": "mobGriefing", "insomnia": "doInsomnia", "patrols": "doPatrolSpawning",
    "traders": "doTraderSpawning", "wardens": "doWardenSpawning", "fire": "doFireTick",
    "block_limit": "commandModificationBlockLimit",
}
_NEW_RULES = {
    "spawning": "spawn_mobs", "daylight": "advance_time", "weather": "advance_weather",
    "griefing": "mob_griefing", "insomnia": "spawn_phantoms", "patrols": "spawn_patrols",
    "traders": "spawn_wandering_traders", "wardens": "spawn_wardens", "fire": "fire_spread_radius_around_player",
    "block_limit": "max_block_modifications",
}
# Value used for each logical rule (the fire rule became an integer radius in 1.21.11).
RULE_VALUES_OLD = {"spawning": "false", "daylight": "false", "weather": "false", "griefing": "false",
                   "insomnia": "false", "patrols": "false", "traders": "false", "wardens": "false",
                   "fire": "false", "block_limit": "1000000"}
RULE_VALUES_NEW = dict(RULE_VALUES_OLD, fire="0")

DIALECTS: dict[str, Dialect] = {
    # 1.20.1: item NBT with a byte Count.
    "1.20.1": Dialect('{{Slot:{slot}b,id:"{id}",Count:{n}b}}', _OLD_RULES, "randomTickSpeed"),
    # 1.20.5+: item stacks are components; lower-case int count.
    "1.21.1": Dialect('{{Slot:{slot}b,id:"{id}",count:{n}}}', _OLD_RULES, "randomTickSpeed"),
    "1.21.11": Dialect('{{Slot:{slot}b,id:"{id}",count:{n}}}', _NEW_RULES, "random_tick_speed"),
    "26.2": Dialect('{{Slot:{slot}b,id:"{id}",count:{n}}}', _NEW_RULES, "random_tick_speed"),
}


def rule_values(mc: str) -> dict[str, str]:
    return RULE_VALUES_NEW if DIALECTS[mc].rules is _NEW_RULES else RULE_VALUES_OLD


# ---------------------------------------------------------------------------------------------
# Targets.
# ---------------------------------------------------------------------------------------------
_FORGE = "https://maven.minecraftforge.net/net/minecraftforge/forge/{b}/forge-{b}-installer.jar"
_NEO = "https://maven.neoforged.net/releases/net/neoforged/neoforge/{b}/neoforge-{b}-installer.jar"
_NEO_1201 = "https://maven.neoforged.net/releases/net/neoforged/forge/{b}/forge-{b}-installer.jar"


@dataclass(frozen=True)
class Target:
    name: str
    mc: str
    loader: str            # forge | neoforge
    build: str             # loader build, as it appears in the installer's file name
    installer_url: str
    java: int              # major version to run the server on
    tps_command: str       # the loader's mean-tick-time command, without the slash

    @property
    def proto(self) -> Proto:
        return PROTOCOLS[self.mc]

    @property
    def dialect(self) -> Dialect:
        return DIALECTS[self.mc]


def _t(mc, loader, build, url, java, tps):
    name = f"{mc}-{loader}"
    return name, Target(name, mc, loader, build, url.format(b=build), java, tps)


TARGETS: dict[str, Target] = dict([
    # NeoForge for 1.20.1 is the Forge 47 fork: /forge tps, Forge-style output.
    _t("1.20.1", "forge", "1.20.1-47.4.23", _FORGE, 17, "forge tps"),
    _t("1.20.1", "neoforge", "1.20.1-47.1.106", _NEO_1201, 17, "forge tps"),
    _t("1.21.1", "forge", "1.21.1-52.1.16", _FORGE, 21, "forge tps"),
    _t("1.21.1", "neoforge", "21.1.252", _NEO, 21, "neoforge tps"),
    _t("1.21.11", "forge", "1.21.11-61.2.1", _FORGE, 21, "forge tps"),
    _t("1.21.11", "neoforge", "21.11.45", _NEO, 21, "neoforge tps"),
    _t("26.2", "forge", "26.2-65.1.3", _FORGE, 25, "forge tps"),
    _t("26.2", "neoforge", "26.2.0.88", _NEO, 25, "neoforge tps"),
])


def java_for(major: int) -> str:
    """Path of a JDK `major` on this box. Override with SKEIN_BENCH_JAVA<major>=/path/to/java."""
    env = os.environ.get(f"SKEIN_BENCH_JAVA{major}")
    if env:
        return env
    home = Path.home()
    candidates = [f"/usr/lib/jvm/java-{major}-openjdk-amd64/bin/java", f"/usr/lib/jvm/openjdk-{major}/bin/java"]
    candidates += sorted(glob.glob(str(home / ".gradle/jdks" / f"*-{major}-*" / "bin/java")))
    candidates += sorted(glob.glob(str(home / ".gradle/jdks" / f"*{major}*" / "bin/java")))
    for c in candidates:
        if os.access(c, os.X_OK):
            return c
    raise SystemExit(f"no Java {major} found; set SKEIN_BENCH_JAVA{major}")
