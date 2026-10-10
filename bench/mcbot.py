"""A silent headless Minecraft client, for 1.20.1 through 26.2 (stdlib only).

The benchmark needs real players online. Before 1.21.5 a chunk is only random-ticked while a
non-spectator player is within 128 blocks (ServerChunkCache.tickChunks), and 1.21.2+ pauses an empty
server. These bots log in offline, answer everything a server waits on (keepalive, ping, teleport
confirm, chunk-batch ack, known packs, player-loaded), and otherwise do nothing. They never send a
movement packet, so the server never moves them. The harness puts them in creative mode, so no mob
targets them.

All per-version packet ids come from targets.PROTOCOLS.
"""
from __future__ import annotations

import hashlib
import socket
import struct
import threading
import time
import uuid as uuidlib
import zlib

from targets import CONFIG_CB, CONFIG_SB, LOGIN_CB, LOGIN_SB, Proto


def varint(n: int) -> bytes:
    n &= 0xFFFFFFFF
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def mc_string(s: str) -> bytes:
    b = s.encode("utf-8")
    return varint(len(b)) + b


def offline_uuid(name: str) -> uuidlib.UUID:
    h = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest())
    h[6] = (h[6] & 0x0F) | 0x30
    h[8] = (h[8] & 0x3F) | 0x80
    return uuidlib.UUID(bytes=bytes(h))


class Reader:
    __slots__ = ("data", "pos")

    def __init__(self, data: bytes, pos: int = 0):
        self.data, self.pos = data, pos

    def varint(self) -> int:
        result = shift = 0
        while True:
            b = self.data[self.pos]
            self.pos += 1
            result |= (b & 0x7F) << shift
            if not b & 0x80:
                break
            shift += 7
        if result & 0x80000000:
            result -= 1 << 32
        return result

    def bytes(self, n: int) -> bytes:
        b = self.data[self.pos:self.pos + n]
        self.pos += n
        return b

    def string(self) -> str:
        return self.bytes(self.varint()).decode("utf-8", "replace")

    def skip(self, n: int) -> None:
        self.pos += n


class Bot:
    def __init__(self, name: str, host: str, port: int, proto: Proto, view_distance: int = 2):
        self.name, self.host, self.port, self.proto = name, host, port, proto
        self.view_distance = view_distance
        self.sock: socket.socket | None = None
        self.phase = "login"
        self.threshold = -1
        self.in_play = threading.Event()
        self.closed = threading.Event()
        self.disconnect_reason: str | None = None
        self.player_loaded_sent = False
        self.pos = (0.0, 0.0, 0.0)
        self.packets = 0
        self.bytes_in = 0
        self._wlock = threading.Lock()
        self._thread: threading.Thread | None = None

    # --- framing -------------------------------------------------------------------------------
    def _send(self, pid: int, payload: bytes = b"") -> None:
        body = varint(pid) + payload
        if self.threshold >= 0:
            if len(body) >= self.threshold:
                comp = zlib.compress(body)
                body = varint(len(body)) + comp
            else:
                body = varint(0) + body
        frame = varint(len(body)) + body
        with self._wlock:
            if self.sock is not None:
                self.sock.sendall(frame)

    def _read_exact(self, n: int) -> bytes:
        buf = bytearray()
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise EOFError("connection closed by server")
            buf += chunk
        return bytes(buf)

    def _read_frame(self) -> tuple[int, bytes]:
        length = shift = 0
        while True:
            b = self._read_exact(1)[0]
            length |= (b & 0x7F) << shift
            if not b & 0x80:
                break
            shift += 7
        data = self._read_exact(length)
        self.bytes_in += length + 1
        r = Reader(data)
        if self.threshold >= 0:
            data_len = r.varint()
            if data_len:
                data = zlib.decompress(data[r.pos:])
                r = Reader(data)
        pid = r.varint()
        return pid, data[r.pos:]

    # --- lifecycle -----------------------------------------------------------------------------
    def connect(self) -> "Bot":
        p = self.proto
        self.sock = socket.create_connection((self.host, self.port), timeout=30)
        self.sock.settimeout(None)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self._send(0x00, varint(p.version) + mc_string(self.host) + struct.pack(">H", self.port) + varint(2))
        uid = offline_uuid(self.name).bytes
        hello = mc_string(self.name) + ((b"\x01" + uid) if p.hello_uuid_flag else uid)
        self._send(LOGIN_SB["hello"], hello)
        self._thread = threading.Thread(target=self._loop, name=f"bot-{self.name}", daemon=True)
        self._thread.start()
        return self

    def close(self) -> None:
        self.closed.set()
        s, self.sock = self.sock, None
        if s is not None:
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            s.close()

    @property
    def online(self) -> bool:
        return self.in_play.is_set() and not self.closed.is_set()

    def _loop(self) -> None:
        try:
            while not self.closed.is_set():
                pid, payload = self._read_frame()
                self.packets += 1
                if self.phase == "login":
                    self._login(pid, Reader(payload))
                elif self.phase == "configuration":
                    self._config(pid, Reader(payload))
                else:
                    self._play(pid, Reader(payload))
        except Exception as e:  # noqa: BLE001 - a bot must never take the harness down
            if not self.closed.is_set() and self.disconnect_reason is None:
                self.disconnect_reason = f"{type(e).__name__}: {e}"
        finally:
            self.closed.set()

    # --- phases --------------------------------------------------------------------------------
    def _client_information(self) -> bytes:
        # ClientInformation: language, viewDistance, chatVisibility FULL, chatColors, skin parts,
        # mainHand RIGHT, textFiltering, allowsListing[, particleStatus ALL (1.21.2+)]
        body = (mc_string("en_us") + struct.pack(">b", self.view_distance) + varint(0) + b"\x01"
                + struct.pack(">B", 0x7F) + varint(1) + b"\x00" + b"\x01")
        return body + (varint(0) if self.proto.particle_status else b"")

    def _login(self, pid: int, r: Reader) -> None:
        if pid == LOGIN_CB["compression"]:
            self.threshold = r.varint()
        elif pid == LOGIN_CB["finished"]:
            if self.proto.config_phase:
                self._send(LOGIN_SB["login_ack"])
                self.phase = "configuration"
                self._send(CONFIG_SB["custom_payload"], mc_string("minecraft:brand") + mc_string("vanilla"))
                self._send(CONFIG_SB["client_info"], self._client_information())
            else:
                # 1.20.1: the server's decoder stays on the LOGIN table until it sends its first PLAY
                # packet, so ClientInformation has to wait for that (see _play).
                self.phase = "play"
        elif pid == LOGIN_CB["custom_query"]:
            # Forge's login handshake. Answer "not understood", like a vanilla client.
            self._send(LOGIN_SB["custom_query_answer"], varint(r.varint()) + b"\x00")
        elif pid == LOGIN_CB["cookie"]:
            self._send(LOGIN_SB["cookie"], mc_string(r.string()) + b"\x00")
        elif pid == LOGIN_CB["hello"]:
            raise EOFError("server asked for encryption (online-mode=true?)")
        elif pid == LOGIN_CB["disconnect"]:
            self.disconnect_reason = "login disconnect: " + r.string()
            raise EOFError(self.disconnect_reason)

    def _config(self, pid: int, r: Reader) -> None:
        if pid == CONFIG_CB["keepalive"]:
            self._send(CONFIG_SB["keepalive"], r.bytes(8))
        elif pid == CONFIG_CB["ping"]:
            # NeoForge pings at the start of configuration and waits for the pong to spot a vanilla client.
            self._send(CONFIG_SB["pong"], r.bytes(4))
        elif pid == CONFIG_CB["known_packs"]:
            packs = [(r.string(), r.string(), r.string()) for _ in range(r.varint())]
            mine = [q for q in packs if q[0] == "minecraft"]
            self._send(CONFIG_SB["known_packs"],
                       varint(len(mine)) + b"".join(mc_string(a) + mc_string(b) + mc_string(c) for a, b, c in mine))
        elif pid == CONFIG_CB["finish"]:
            self._send(CONFIG_SB["finish"])
            self.phase = "play"
            self.in_play.set()
        elif pid == CONFIG_CB["code_of_conduct"]:
            self._send(CONFIG_SB["accept_code_of_conduct"])
        elif pid == CONFIG_CB["resource_pack_push"]:
            pack = r.bytes(16)
            for action in (3, 4, 0):
                self._send(CONFIG_SB["resource_pack"], pack + varint(action))
        elif pid == CONFIG_CB["cookie"]:
            self._send(CONFIG_SB["cookie"], mc_string(r.string()) + b"\x00")
        elif pid == CONFIG_CB["disconnect"]:
            self.disconnect_reason = "config disconnect"
            raise EOFError(self.disconnect_reason)

    def _play(self, pid: int, r: Reader) -> None:
        p = self.proto
        if not self.in_play.is_set():
            if p.sb_client_info is not None:
                self._send(p.sb_client_info, self._client_information())
            self.in_play.set()
        if pid == p.cb_keepalive:
            self._send(p.sb_keepalive, r.bytes(8))
        elif pid == p.cb_ping:
            self._send(p.sb_pong, r.bytes(4))
        elif pid == p.cb_position:
            if p.pos_id_first:      # 1.21.2+: id, position, delta, yRot, xRot, int relative-flags
                tid = r.varint()
                xyz = struct.unpack(">ddd", r.bytes(24))
                r.skip(24 + 8)
                (rel,) = struct.unpack(">i", r.bytes(4))
            else:                   # x, y, z, yRot, xRot, byte relative-flags, id
                xyz = struct.unpack(">ddd", r.bytes(24))
                r.skip(8)
                rel = r.bytes(1)[0]
                tid = r.varint()
            self.pos = tuple(xyz[i] + (self.pos[i] if rel & (1 << i) else 0.0) for i in range(3))
            self._send(p.sb_accept_teleport, varint(tid))
            if p.sb_player_loaded is not None and not self.player_loaded_sent:
                self._send(p.sb_player_loaded)
                self.player_loaded_sent = True
            # A vanilla client always follows the confirm with a position packet, and that packet is
            # what moves the player's chunk tickets and natural-spawn tracking (ChunkMap.move).
            # Without it the server keeps both at the login spot, however far /tp moves the player.
            self._send(p.sb_move_pos, struct.pack(">ddd", *self.pos) + b"\x00")
        elif p.cb_chunk_batch_finished is not None and pid == p.cb_chunk_batch_finished:
            self._send(p.sb_chunk_batch_received, struct.pack(">f", 20.0))
        elif p.cb_start_configuration is not None and pid == p.cb_start_configuration:
            self._send(p.sb_config_ack)
            self.phase = "configuration"
            self.in_play.clear()
        elif pid == p.cb_disconnect:
            self.disconnect_reason = "play disconnect"
            raise EOFError(self.disconnect_reason)


def connect_all(names: list[str], host: str, port: int, proto: Proto, timeout: float = 60.0) -> list[Bot]:
    """Log bots in one after another; the server throttles logins from one address."""
    bots = []
    for n in names:
        # Forge 26.2 occasionally closes the first login of a freshly started vanilla server;
        # the same bot joins fine a moment later. Retry before failing the leg.
        for attempt in range(3):
            b = Bot(n, host, port, proto).connect()
            deadline = time.monotonic() + timeout
            while not b.in_play.is_set() and not b.closed.is_set() and time.monotonic() < deadline:
                time.sleep(0.05)
            if b.in_play.is_set():
                break
            reason = b.disconnect_reason or "timed out"
            b.close()
            if attempt == 2:
                for x in bots:
                    x.close()
                raise RuntimeError(f"bot {n} failed to join: {reason}")
            time.sleep(3)
        bots.append(b)
        time.sleep(0.3)
    return bots
