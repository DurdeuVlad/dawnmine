"""Generate the minimal .nbt structure template used by the M1 game tests.

The game tests only need a resolvable 1x1x1 template; content is irrelevant.
Writes a gzip-compressed NBT file matching the structure template format:
size [1,1,1], single minecraft:air block at 0,0,0, no entities.
"""

import gzip
import os
import struct
import sys

TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12

DATA_VERSION_1_21_1 = 3955


def tag_header(tag_id: int, name: str) -> bytes:
    encoded = name.encode("utf-8")
    return bytes([tag_id]) + struct.pack(">H", len(encoded)) + encoded


def int_tag(name: str, value: int) -> bytes:
    return tag_header(TAG_INT, name) + struct.pack(">i", value)


def string_tag(name: str, value: str) -> bytes:
    encoded = value.encode("utf-8")
    return tag_header(TAG_STRING, name) + struct.pack(">H", len(encoded)) + encoded


def int_array_tag(name: str, values: list[int]) -> bytes:
    payload = struct.pack(">i", len(values)) + b"".join(struct.pack(">i", v) for v in values)
    return tag_header(TAG_INT_ARRAY, name) + payload


def list_tag(name: str, element_tag_id: int, elements: list[bytes]) -> bytes:
    return tag_header(TAG_LIST, name) + bytes([element_tag_id]) + struct.pack(">i", len(elements)) + b"".join(elements)


def compound_payload(children: list[bytes]) -> bytes:
    return b"".join(children) + bytes([TAG_END])


def build() -> bytes:
    palette_entry = compound_payload([string_tag("Name", "minecraft:air")])
    block_entry = compound_payload([
        int_array_tag("pos", [0, 0, 0]),
        int_tag("state", 0),
    ])
    root = compound_payload([
        int_array_tag("size", [1, 1, 1]),
        list_tag("palette", TAG_COMPOUND, [palette_entry]),
        list_tag("blocks", TAG_COMPOUND, [block_entry]),
        list_tag("entities", TAG_COMPOUND, []),
        int_tag("DataVersion", DATA_VERSION_1_21_1),
    ])
    return bytes([TAG_COMPOUND]) + struct.pack(">H", 0) + root


def main() -> None:
    if len(sys.argv) != 2:
        print("usage: make_empty_structure.py <output.nbt>", file=sys.stderr)
        sys.exit(2)
    path = sys.argv[1]
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with gzip.open(path, "wb") as handle:
        handle.write(build())
    print(f"wrote {path} ({os.path.getsize(path)} bytes)")


if __name__ == "__main__":
    main()
