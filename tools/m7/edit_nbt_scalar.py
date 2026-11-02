"""Set a top-level scalar field (int or string) inside a gzipped NBT file.

Usage: python tools/m7/edit_nbt_scalar.py <file> <field> <type> <value>
  type: "int" (4-byte) or "string" (utf-8, value may differ in length)

Only supports scalar fields directly under the root compound, which is enough
to simulate lifecycle states in publicmines_run.dat for M7 verification.
"""

import gzip
import os
import struct
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "m1"))
from dump_nbt import _val  # noqa: E402

TAG_INT = 3
TAG_STRING = 8
TAG_COMPOUND = 10


def _find_field(buf: bytes, name: str, want_tag: int):
    """Return (payload_start, payload_end) inside the root compound."""
    i = 0
    while True:
        tag = buf[i]
        if tag == 0:
            raise KeyError("field not found: " + name)
        ln = struct.unpack(">H", buf[i + 1:i + 3])[0]
        field = buf[i + 3:i + 3 + ln].decode("utf-8")
        payload = i + 3 + ln
        if field == name:
            if tag != want_tag:
                raise ValueError(f"field {name} has tag {tag}, expected {want_tag}")
            if want_tag == TAG_INT:
                return payload, payload + 4
            strlen = struct.unpack(">H", buf[payload:payload + 2])[0]
            return payload, payload + 2 + strlen
        _, i = _val(buf, payload, tag)


def main() -> int:
    path, field, ftype, value = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
    raw = gzip.open(path, "rb").read()
    # root: tag(1) + name len(2) + name + payload compound
    name_len = struct.unpack(">H", raw[1:3])[0]
    payload_at = 3 + name_len
    root = raw[payload_at:]
    # SavedData files wrap the payload in a nested "data" compound.
    try:
        dstart, _ = _find_field(root, "data", TAG_COMPOUND)
        root = root[dstart:]
        payload_at += dstart
    except KeyError:
        pass  # fields are directly on the root compound
    want = TAG_INT if ftype == "int" else TAG_STRING
    start, end = _find_field(root, field, want)
    if ftype == "int":
        new = struct.pack(">i", int(value))
        patched = raw[:payload_at + start] + new + raw[payload_at + end:]
    else:
        enc = value.encode("utf-8")
        patched = (raw[:payload_at + start] + struct.pack(">H", len(enc))
                   + enc + raw[payload_at + end:])
    with gzip.open(path, "wb") as out:
        out.write(patched)
    print(f"patched {field} ({ftype}) -> {value} in {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
