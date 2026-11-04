import importlib.util
import io
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

spec = importlib.util.spec_from_file_location(
    "copy_dispatch_data",
    Path(__file__).parent / "copy-dispatch-data.py")
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

DISPATCH = b"dispatch-bytes-v1"
CONFIG = b"config-bytes-v1"


def make_world(root, dispatch=True, config=True):
    w = Path(root) / "world"
    (w / "data").mkdir(parents=True)
    (w / "serverconfig").mkdir(parents=True)
    if dispatch:
        (w / "data" / "publicmines_dispatch.dat").write_bytes(DISPATCH)
    if config:
        (w / "serverconfig" / "publicmines-server.toml").write_bytes(CONFIG)
    return w


def run(world):
    buf = io.StringIO()
    argv = sys.argv
    try:
        sys.argv = ["x", str(world)]
        with redirect_stdout(buf):
            code = mod.main()
    finally:
        sys.argv = argv
    return code, buf.getvalue()


class MigrationTests(unittest.TestCase):
    def test_exact_bytes_and_sources_retained(self):
        with tempfile.TemporaryDirectory() as tmp:
            w = make_world(tmp)
            code, out = run(w)
            self.assertEqual(code, 0, out)
            self.assertEqual(
                (w / "data" / "dawnmine_dispatch.dat").read_bytes(), DISPATCH)
            self.assertEqual(
                (w / "serverconfig" / "dawnmine-server.toml").read_bytes(), CONFIG)
            self.assertTrue((w / "data" / "publicmines_dispatch.dat").exists())
            self.assertTrue(
                (w / "serverconfig" / "publicmines-server.toml").exists())

    def test_rerun_is_idempotent(self):
        with tempfile.TemporaryDirectory() as tmp:
            w = make_world(tmp)
            self.assertEqual(run(w)[0], 0)
            code, out = run(w)
            self.assertEqual(code, 0, out)
            self.assertIn("already migrated", out)

    def test_conflicting_destination_fails_nonzero(self):
        with tempfile.TemporaryDirectory() as tmp:
            w = make_world(tmp)
            (w / "data" / "dawnmine_dispatch.dat").write_bytes(b"different")
            code, out = run(w)
            self.assertEqual(code, 1)
            self.assertEqual(
                (w / "data" / "dawnmine_dispatch.dat").read_bytes(), b"different")
            self.assertIn("conflicting destination", out)

    def test_missing_world_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, _ = run(Path(tmp) / "nope")
            self.assertEqual(code, 2)

    def test_missing_dispatch_preflight_fails_nonzero(self):
        with tempfile.TemporaryDirectory() as tmp:
            w = make_world(tmp, dispatch=False)
            code, out = run(w)
            self.assertEqual(code, 1)
            self.assertFalse(
                (w / "serverconfig" / "dawnmine-server.toml").exists(),
                "partial copy must not happen on preflight failure")
            self.assertIn("missing source", out)

    def test_partial_preflight_failure_copies_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            w = make_world(tmp, dispatch=False)
            # dispatch missing AND config destination conflicts -> nothing copied
            (w / "serverconfig" / "dawnmine-server.toml").write_bytes(b"other")
            code, _ = run(w)
            self.assertEqual(code, 1)
            self.assertEqual(
                (w / "serverconfig" / "dawnmine-server.toml").read_bytes(), b"other")

    def test_interrupted_copy_never_publishes_partial_destination(self):
        with tempfile.TemporaryDirectory() as tmp:
            w = make_world(tmp)

            def interrupted(source, destination):
                destination.write(b"partial")
                raise OSError("simulated disk full")

            with mock.patch.object(mod.shutil, "copyfileobj", side_effect=interrupted):
                code, out = run(w)
            self.assertEqual(code, 1, out)
            self.assertFalse((w / "data" / "dawnmine_dispatch.dat").exists())
            self.assertEqual((w / "data" / "publicmines_dispatch.dat").read_bytes(), DISPATCH)
            self.assertEqual(list((w / "data").glob("*.tmp")), [])
            self.assertEqual(run(w)[0], 0, "a clean retry should finish")

    def test_destination_created_after_preflight_is_never_replaced(self):
        with tempfile.TemporaryDirectory() as tmp:
            w = make_world(tmp)
            link = mod.os.link

            def concurrent_destination(source, destination):
                Path(destination).write_bytes(b"another-writer")
                return link(source, destination)

            with mock.patch.object(mod.os, "link", side_effect=concurrent_destination):
                code, out = run(w)
            self.assertEqual(code, 1, out)
            self.assertEqual((w / "data" / "dawnmine_dispatch.dat").read_bytes(), b"another-writer")
            self.assertEqual((w / "data" / "publicmines_dispatch.dat").read_bytes(), DISPATCH)


if __name__ == "__main__":
    unittest.main()
