"""Dump a gzip-compressed Minecraft NBT file as JSON for verification evidence.

Usage: python tools/m1/dump_nbt.py <path-to-dat-or-nbt>
"""

import gzip
import json
import struct
import sys


def _val(buf: bytes, i: int, tag: int):
    if tag == 0:
        return None, i
    if tag == 1:
        return struct.unpack(">b", buf[i:i + 1])[0], i + 1
    if tag == 2:
        return struct.unpack(">h", buf[i:i + 2])[0], i + 2
    if tag == 3:
        return struct.unpack(">i", buf[i:i + 4])[0], i + 4
    if tag == 4:
        return struct.unpack(">q", buf[i:i + 8])[0], i + 8
    if tag == 5:
        return struct.unpack(">f", buf[i:i + 4])[0], i + 4
    if tag == 6:
        return struct.unpack(">d", buf[i:i + 8])[0], i + 8
    if tag == 7:
        n = struct.unpack(">i", buf[i:i + 4])[0]
        i += 4
        return buf[i:i + n].hex(), i + n
    if tag == 8:
        ln = struct.unpack(">H", buf[i:i + 2])[0]
        i += 2
        return buf[i:i + ln].decode("utf-8"), i + ln
    if tag == 9:
        et = buf[i]
        n = struct.unpack(">i", buf[i + 1:i + 5])[0]
        i += 5
        out = []
        for _ in range(n):
            v, i = _val(buf, i, et)
            out.append(v)
        return out, i
    if tag == 10:
        out = {}
        while True:
            if buf[i] == 0:
                return out, i + 1
            name, v, i = _named(buf, i)
            out[name] = v
    if tag == 11:
        n = struct.unpack(">i", buf[i:i + 4])[0]
        i += 4
        return list(struct.unpack(">%di" % n, buf[i:i + 4 * n])), i + 4 * n
    if tag == 12:
        n = struct.unpack(">i", buf[i:i + 4])[0]
        i += 4
        return list(struct.unpack(">%dq" % n, buf[i:i + 8 * n])), i + 8 * n
    raise ValueError("unknown tag %d" % tag)


def _named(buf: bytes, i: int):
    tag = buf[i]
    i += 1
    ln = struct.unpack(">H", buf[i:i + 2])[0]
    i += 2
    name = buf[i:i + ln].decode("utf-8")
    i += ln
    v, i = _val(buf, i, tag)
    return name, v, i


def main() -> None:
    path = sys.argv[1]
    data = gzip.open(path, "rb").read()
    name, value, _ = _named(data, 0)
    print(json.dumps({name: value}, indent=1, default=str))


if __name__ == "__main__":
    main()
