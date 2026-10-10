"""Minimal Source-RCON client for Minecraft (stdlib only).

Two server behaviours shape it:

* Minecraft's RconClient reads each request with one 1460-byte socket read and drops the connection
  if that read does not hold exactly one packet. So two requests must never sit in the server's socket
  buffer at once. Requests go one at a time, and nothing is pipelined behind a command.
* Replies are split into 4096-character packets. Vanilla and NeoForge always send at least one packet,
  even an empty one. Forge's sendCmdResponse loops `for (x = 0; x < len; x += 4096)`, so a command with
  empty output (a gamemode that changes nothing, a silent execute) gets no reply at all.

So: a reply packet shorter than 4096 characters ends the answer. If nothing has arrived after 0.25 s,
a sentinel `list` (which always answers) goes out on the same connection. By then the server has long
since read the command, so the sentinel is alone in its buffer. Everything the command will ever say
arrives before the sentinel's reply, and an empty answer comes back as "".
"""
from __future__ import annotations

import select
import socket
import struct
import threading
import time

_AUTH, _EXEC = 3, 2
_CHUNK = 4096
_QUIET_S = 0.25


class RconError(Exception):
    pass


class Rcon:
    def __init__(self, host: str, port: int, password: str, timeout: float = 120.0):
        self.host, self.port, self.password, self.timeout = host, port, password, timeout
        self.sock: socket.socket | None = None
        self._id = 0
        self._lock = threading.Lock()

    def connect(self, deadline: float) -> "Rcon":
        last = None
        while time.monotonic() < deadline:
            try:
                s = socket.create_connection((self.host, self.port), timeout=5)
                s.settimeout(self.timeout)
                self.sock = s
                rid = self._send(_AUTH, self.password)
                pid, _ = self._recv()
                if pid == -1 or pid != rid:
                    raise RconError("RCON auth failed")
                return self
            except (OSError, RconError) as e:
                last = e
                self.close()
                time.sleep(1.0)
        raise RconError(f"RCON connect failed: {last}")

    def close(self) -> None:
        if self.sock:
            try:
                self.sock.close()
            except OSError:
                pass
        self.sock = None

    def _send(self, kind: int, body: str) -> int:
        self._id += 1
        data = struct.pack("<ii", self._id, kind) + body.encode("utf-8") + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(data)) + data)
        return self._id

    def _read_exact(self, n: int) -> bytes:
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise RconError("RCON connection closed")
            buf += chunk
        return buf

    def _recv(self) -> tuple[int, str]:
        (length,) = struct.unpack("<i", self._read_exact(4))
        data = self._read_exact(length)
        rid, _kind = struct.unpack("<ii", data[:8])
        return rid, data[8:-2].decode("utf-8", "replace")

    def _readable(self, seconds: float) -> bool:
        # wait for the start of a packet without ever timing out half-way through one
        return bool(select.select([self.sock], [], [], seconds)[0])

    def command(self, cmd: str) -> str:
        if len(cmd.encode("utf-8")) > 1400:
            raise RconError(f"command too long for Minecraft's RCON reader ({len(cmd)} chars)")
        with self._lock:
            try:
                return self._command(cmd)
            except (RconError, OSError) as e:
                # If the server's single read ever caught two packets, it closes the connection before
                # running either of them, so reconnecting and resending cannot run the command twice.
                if "closed" not in str(e) and not isinstance(e, (ConnectionResetError, BrokenPipeError)):
                    raise
                self.close()
                self.connect(time.monotonic() + 30)
                return self._command(cmd)

    def _command(self, cmd: str) -> str:
        rid = self._send(_EXEC, cmd)
        out: list[str] = []
        sid = None
        deadline = time.monotonic() + self.timeout
        while True:
            if sid is None and not self._readable(_QUIET_S):
                sid = self._send(_EXEC, "list")          # nothing yet: fence with a command that answers
                continue
            if sid is not None and not self._readable(max(0.0, deadline - time.monotonic())):
                raise RconError(f"no RCON reply to /{cmd} within {self.timeout:.0f} s")
            pid, body = self._recv()
            if pid == rid:
                out.append(body)
                if sid is None and len(body) < _CHUNK:
                    return "".join(out)
            elif pid == sid:
                return "".join(out)
