"""Set a top-level string field inside a gzipped NBT file (test fixture tool).

Usage: python tools/m1/edit_nbt.py <file> <path.to.string.field> <new value>
Only supports editing string fields under nested compounds, which is enough
to simulate lifecycle states in publicmines_run.dat for M1 verification.
"""

import gzip
import struct
import sys

from dump_nbt import _named, _val

TAG_STRING = 8
TAG_COMPOUND = 10


def _skip_name(buf: bytes, i: int) -> int:
    ln = struct.unpack(">H", buf[i:i + 2])[0]
    return i + 2 + ln


def _find_string_field(buf: bytes, parts: list[str]):
    """Return (value_start, value_end) byte range of a nested string field."""
    # buf starts inside a compound (after its tag+name header)
    i = 0
    while True:
        tag = buf[i]
        if tag == 0:
            raise KeyError("field not found: " + ".".join(parts))
        name_start = i + 1
        ln = struct.unpack(">H", buf[name_start:name_start + 2])[0]
        name = buf[name_start + 2:name_start + 2 + ln].decode("utf-8")
        i += 1 + 2 + ln
        if name == parts[0]:
            if len(parts) == 1:
                if tag != TAG_STRING:
                    raise ValueError("field is not a string: " + name)
                return i, i + 2
            if tag != TAG_COMPOUND:
                raise ValueError("field is not a compound: " + name)
            inner_start, inner_end = _find_string_field_offset(buf, i, parts[1:])
            return inner_start, inner_end
        _, i = _val(buf, i, tag)


def _find_string_field_offset(buf: bytes, i: int, parts: list[str]):
    sub = buf[i:]
    start, _ = _find_string_field(sub, parts)
    # recompute absolute end: value_start points at the 2-byte length prefix
    vlen = struct.unpack(">H", buf[i + start:i + start + 2])[0]
    return i + start, i + start + 2 + vlen


def main() -> None:
    path, dotted, new_value = sys.argv[1], sys.argv[2].split("."), sys.argv[3]
    raw = gzip.open(path, "rb").read()
    # root is a named compound; enter it, then "data" compound per SavedData format
    i = _skip_name(raw, 1)
    start, end = _find_string_field_offset(raw, i, ["data"] + dotted)
    encoded = new_value.encode("utf-8")
    replacement = struct.pack(">H", len(encoded)) + encoded
    new_raw = raw[:start] + replacement + raw[end:]
    with gzip.open(path, "wb") as handle:
        handle.write(new_raw)
    print(f"set {'.'.join(dotted)} = {new_value!r} in {path}")


if __name__ == "__main__":
    main()
