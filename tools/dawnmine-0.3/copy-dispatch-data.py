#!/usr/bin/env python3
"""Dawnmine 0.3 offline migration helper (non-destructive).

Copies 0.2-era shared data into the new Dawnmine file names WITHOUT
overwriting or deleting anything:

  <world>/data/publicmines_dispatch.dat      -> <world>/data/dawnmine_dispatch.dat
  <world>/serverconfig/publicmines-server.toml -> <world>/serverconfig/dawnmine-server.toml

Rules: preflight ALL pairs before copying anything; destinations are created
atomically without replacement; a byte-identical existing destination is
treated as already-migrated (idempotent rerun); any missing source, missing
world, conflicting non-identical destination, or partial failure exits nonzero
and reports, leaving every user file untouched. Run OFFLINE, after a backup.
Run progress (publicmines_run.dat) is intentionally NOT copied.

Usage: python copy-dispatch-data.py <path-to-world-folder>
"""
import filecmp
import os
import shutil
import sys
import tempfile
from pathlib import Path

PAIRS = [
    ("data/publicmines_dispatch.dat", "data/dawnmine_dispatch.dat"),
    ("serverconfig/publicmines-server.toml", "serverconfig/dawnmine-server.toml"),
]


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    world = Path(sys.argv[1])
    if not world.is_dir():
        print(f"error: {world} is not a world directory")
        return 2

    # Preflight: classify every pair before any copy happens.
    plan, problems = [], []
    for src_rel, dst_rel in PAIRS:
        src, dst = world / src_rel, world / dst_rel
        if not src.is_file():
            problems.append(f"missing source: {src_rel}")
            continue
        if dst.exists():
            if dst.is_file() and filecmp.cmp(src, dst, shallow=False):
                plan.append(("identical", src_rel, dst_rel))
            else:
                problems.append(
                    f"conflicting destination (differs, not overwritten): {dst_rel}")
            continue
        plan.append(("copy", src_rel, dst_rel))
    if problems:
        for problem in problems:
            print(f"[fail] {problem}")
        print("aborted: no files were copied; resolve the conflicts and rerun")
        return 1

    copied = identical = 0
    for action, src_rel, dst_rel in plan:
        src, dst = world / src_rel, world / dst_rel
        if action == "identical":
            print(f"[ok]   {dst_rel}: already migrated (byte-identical)")
            identical += 1
            continue
        temporary = None
        try:
            # Publish only a complete, verified copy. A hard link is atomic
            # and refuses an existing destination, unlike os.replace().
            with tempfile.NamedTemporaryFile(
                    mode="wb", dir=dst.parent, prefix=f".{dst.name}.",
                    suffix=".tmp", delete=False) as out:
                temporary = Path(out.name)
                with open(src, "rb") as inp:
                    shutil.copyfileobj(inp, out)
                out.flush()
                os.fsync(out.fileno())
            if not filecmp.cmp(src, temporary, shallow=False):
                raise IOError("copy verification failed (bytes differ)")
            os.link(temporary, dst)
        except (OSError, IOError) as exc:
            print(f"[fail] {dst_rel}: {exc}")
            print("aborted: existing files were not replaced; completed "
                  "copies remain valid, and incomplete copies were not published")
            return 1
        finally:
            if temporary is not None:
                try:
                    temporary.unlink()
                except OSError as exc:
                    print(f"[warn] could not remove migration temporary file: {exc}")
        print(f"[copy] {src_rel} -> {dst_rel} (original kept)")
        copied += 1
    print(f"done: {copied} copied, {identical} already migrated, 0 overwritten.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
