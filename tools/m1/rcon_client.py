"""Minimal RCON client used by the M1 dedicated-server verification harness.

Usage:
    python tools/m1/rcon_client.py <host> <port> <password> <command> [command...]

Each command is sent and its response printed to stdout. Exit code is 0 when
every command round-tripped successfully.
"""

import socket
import struct
import sys

TYPE_AUTH = 3
TYPE_COMMAND = 2


def _packet(request_id: int, packet_type: int, payload: str) -> bytes:
    body = struct.pack("<ii", request_id, packet_type) + payload.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(body)) + body


def _read_packet(sock: socket.socket) -> tuple[int, int, str]:
    header = _recv_exact(sock, 4)
    (length,) = struct.unpack("<i", header)
    data = _recv_exact(sock, length)
    request_id, packet_type = struct.unpack("<ii", data[:8])
    return request_id, packet_type, data[8:-2].decode("utf-8", errors="replace")


def _recv_exact(sock: socket.socket, count: int) -> bytes:
    chunks = []
    remaining = count
    while remaining > 0:
        chunk = sock.recv(remaining)
        if not chunk:
            raise ConnectionError("rcon connection closed")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def main() -> int:
    host, port, password = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    commands = sys.argv[4:]
    if not commands:
        print("no commands given", file=sys.stderr)
        return 2

    with socket.create_connection((host, port), timeout=60) as sock:
        sock.settimeout(60)
        sock.sendall(_packet(1, TYPE_AUTH, password))
        request_id, _, _ = _read_packet(sock)
        if request_id == -1:
            print("rcon authentication failed", file=sys.stderr)
            return 3
        for index, command in enumerate(commands, start=2):
            sock.sendall(_packet(index, TYPE_COMMAND, command))
            # the server may send several packets for one command; read until we
            # see our request id on a command-response packet
            while True:
                response_id, packet_type, payload = _read_packet(sock)
                if packet_type == 0 and response_id == index:
                    break
            print(f"$ {command}")
            print(payload)
            print("---")
    return 0


if __name__ == "__main__":
    sys.exit(main())
