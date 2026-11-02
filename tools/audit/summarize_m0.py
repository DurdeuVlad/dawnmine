#!/usr/bin/env python3
"""Rebuild the M0 ore/Born-in-Chaos tables and pack JAR hash list."""

from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import json
import re
import zipfile
from collections import Counter
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def read_runtime_tags(path: Path):
    raw = path.read_bytes()
    rows = list(csv.reader(raw.decode("utf-8").splitlines(), delimiter="\t"))
    if not rows or rows[0] != ["block_id", "tag_id"]:
        raise ValueError(f"Expected tab-delimited block_id/tag_id header in {path}")
    all_blocks: set[str] = set()
    tags_by_block: dict[str, set[str]] = {}
    for row in rows[1:]:
        if len(row) != 2:
            raise ValueError(f"Malformed runtime tag row: {row!r}")
        block_id, tag_id = row
        all_blocks.add(block_id)
        if tag_id:
            tags_by_block.setdefault(block_id, set()).add(tag_id)
    return raw, rows, all_blocks, tags_by_block


# Ore-like block-name signals beyond the *_ore convention, found by a
# name sweep over the full runtime block registry. `salt` is matched as a
# token so names like `basalt` are not swept in.
ORE_NAME_SWEEP_RE = re.compile(r"(deposit|vein|formation)", re.IGNORECASE)


def ore_like_name(name: str) -> bool:
    return bool(ORE_NAME_SWEEP_RE.search(name)) or any(
        token.startswith("salt") for token in name.split("_")
    )


# Loot outputs that indicate a block is a material source rather than decor.
# Matched against the item path (after the namespace).
MATERIAL_OUTPUT_RE = re.compile(
    r"(raw_|_ore$|_ingot|_nugget|_gem\b|citrine|jade|ruby|sapphire|tanzanite|topaz|"
    r"quartz|diamond$|emerald$|coal$|lapis|redstone$|(?:^|_)salt(?:_|$)|sulfur|"
    r"pieceofdarkmetal|shard$|crystal$|amethyst|charcoal$)",
    re.IGNORECASE,
)


def registry_candidate_ids(
    all_blocks: set[str],
    tags_by_block: dict[str, set[str]],
    outputs_by_block: dict[str, set[str]] | None = None,
) -> dict[str, str]:
    """Return candidate block_id -> candidate basis category."""
    out: dict[str, str] = {}

    def put(block: str, basis: str) -> None:
        out.setdefault(block, basis)

    for block in all_blocks:
        tags = tags_by_block.get(block, set())
        name = block.split(":", 1)[1]
        if any(tag == "c:ores" or tag.startswith("c:ores/") for tag in tags):
            put(block, "resolved common c:ores family")
            continue
        if any(
            re.search(r"_ores?$", tag.split(":", 1)[-1]) and not tag.startswith("c:")
            for tag in tags
        ):
            put(block, "resolved mod-specific ore tag")
            continue
        if name.endswith("_ore"):
            put(block, "runtime block registry ID ends in _ore; no ore-family tag found")
            continue
        if ore_like_name(name):
            if outputs_by_block is None:
                put(block, "runtime name sweep: ore-like block name")
                continue
            outputs = outputs_by_block.get(block)
            if outputs is None or any(o != block for o in outputs):
                put(block, "runtime name sweep: ore-like name with material drops or unevaluated loot")
            continue
        if outputs_by_block is not None:
            outputs = outputs_by_block.get(block)
            if outputs and any(
                MATERIAL_OUTPUT_RE.search(o.rsplit(":", 1)[-1]) and o != block
                for o in outputs
            ):
                put(block, "runtime loot-output sweep: material drops, no ore signals")
    return out


def jar_evidence(mods_dir: Path):
    """Scan every pack JAR: blockstate resources and all block loot tables."""
    blockstates: dict[str, tuple[str, str]] = {}
    loot_tables: dict[str, tuple[str, str, list[str]]] = {}
    for jar in sorted(mods_dir.glob("*.jar")):
        try:
            with zipfile.ZipFile(jar) as archive:
                for name in archive.namelist():
                    if name.startswith("assets/") and "/blockstates/" in name and name.endswith(".json"):
                        namespace = name.split("/")[1]
                        block = f"{namespace}:{name.rsplit('/', 1)[1][:-5]}"
                        blockstates.setdefault(block, (name, jar.name))
                    elif (
                        name.startswith("data/")
                        and ("/loot_table/blocks/" in name or "/loot_tables/blocks/" in name)
                        and name.endswith(".json")
                    ):
                        namespace = name.split("/")[1]
                        block_path = name.split("/blocks/", 1)[1][:-5]
                        block = f"{namespace}:{block_path}"
                        if block in loot_tables:
                            continue
                        try:
                            table = json.loads(archive.read(name))
                            outputs: set[str] = set()

                            def visit(value):
                                if isinstance(value, dict):
                                    if value.get("type") == "minecraft:item" and isinstance(value.get("name"), str):
                                        outputs.add(value["name"])
                                    for child in value.values():
                                        visit(child)
                                elif isinstance(value, list):
                                    for child in value:
                                        visit(child)

                            visit(table)
                            loot_tables[block] = (name, jar.name, sorted(outputs))
                        except (json.JSONDecodeError, UnicodeDecodeError):
                            loot_tables[block] = (name, jar.name, [])
        except zipfile.BadZipFile:
            continue
    return blockstates, loot_tables


def jar_hashes(mods_dir: Path, jar_names: set[str]) -> dict[str, str]:
    return {name: sha256(mods_dir / name) for name in sorted(jar_names)}


BASIS_ASSESSMENT = {
    "resolved common c:ores family": "common-tagged resource candidate; confirm mine tier and native drops",
    "resolved mod-specific ore tag": "special mod-tagged candidate; confirm intended mining/drop behavior",
    "runtime name sweep: ore-like name with material drops or unevaluated loot": (
        "name-sweep candidate (deposit/vein/formation/salt); material drops; confirm ore-tier classification"
    ),
    "runtime name sweep: ore-like block name": "name-sweep candidate; loot evidence unavailable",
    "runtime loot-output sweep: material drops, no ore signals": (
        "loot-sweep candidate; material drops without ore signals; classify as ore tier vs salvage/loot node"
    ),
}


def write_ore_candidates(out_dir: Path, candidates: dict[str, str], tags_by_block, blockstates, loot_tables, hashes) -> int:
    path = out_dir / "M0-ore-candidates.csv"
    fields = [
        "block_id",
        "runtime_ore_tags",
        "candidate_basis",
        "blockstate_resource",
        "blockstate_source_jar",
        "blockstate_source_sha256",
        "loot_table_resource",
        "loot_source_jar",
        "loot_source_sha256",
        "static_item_outputs",
        "assessment",
    ]
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for block in sorted(candidates):
            basis = candidates[block]
            tags = sorted(
                tag
                for tag in tags_by_block.get(block, set())
                if tag == "c:ores"
                or tag.startswith("c:ores/")
                or (re.search(r"_ores?$", tag.split(":", 1)[-1]) and not tag.startswith("c:"))
            )
            bs_res, bs_jar = blockstates.get(block, ("", ""))
            lt = loot_tables.get(block, ("", "", []))
            assessment = BASIS_ASSESSMENT.get(basis)
            if basis == "runtime block registry ID ends in _ore; no ore-family tag found":
                assessment = (
                    "untagged ore-name candidate; inspect loot table and verify native block-break behavior"
                    if lt[0]
                    else "untagged ore-name candidate; static drop behavior unresolved; verify in game"
                )
            if block == "minecraft:ancient_debris":
                assessment = "common-tagged special case; path does not end in _ore"
            writer.writerow(
                {
                    "block_id": block,
                    "runtime_ore_tags": ";".join(tags),
                    "candidate_basis": basis,
                    "blockstate_resource": bs_res,
                    "blockstate_source_jar": bs_jar or "Minecraft 1.21.1 built-in",
                    "blockstate_source_sha256": hashes.get(bs_jar, "") if bs_jar else "",
                    "loot_table_resource": lt[0],
                    "loot_source_jar": lt[1] or "Minecraft 1.21.1 built-in or absent",
                    "loot_source_sha256": hashes.get(lt[1], "") if lt[1] else "",
                    "static_item_outputs": ";".join(lt[2]),
                    "assessment": assessment,
                }
            )
    return len(candidates)


def read_bic_registration_dump(path: Path):
    text = path.read_text(encoding="utf-8")
    if "BootstrapMethods:" not in text or "static {};" not in text:
        raise ValueError("Expected `javap -v -p` output for BornInChaosV1ModEntities")
    bootstrap = text.split("BootstrapMethods:", 1)[1]
    parts = re.split(r"\n\s*(\d+): #", bootstrap)
    factories: dict[int, str] = {}
    for index in range(1, len(parts), 2):
        entry = "\n".join(parts[index + 1].splitlines()[:12])
        constructor = re.search(r'REF_newInvokeSpecial\s+([\w/$]+)\."<init>"', entry)
        if constructor:
            factories[int(parts[index])] = constructor.group(1).replace("/", ".")

    method = text.split("  static {};", 1)[1].split("BootstrapMethods:", 1)[0]
    lines = method.splitlines()
    registrations = set()
    for index, line in enumerate(lines):
        name = re.search(r"ldc_w\s+#\d+\s+// String ([\w]+)", line)
        if not name or name.group(1) == "born_in_chaos_v1":
            continue
        following = "\n".join(lines[index + 1 : index + 6])
        factory = re.search(r"InvokeDynamic #(\d+):create", following)
        category = re.search(r"MobCategory\.(\w+)", following)
        if not factory or not category:
            continue
        factory_index = int(factory.group(1))
        if factory_index not in factories:
            raise ValueError(f"No constructor mapping for invokedynamic #{factory_index}")
        registrations.add(
            (f"born_in_chaos_v1:{name.group(1)}", factories[factory_index], category.group(1))
        )
    if len(registrations) != 98:
        raise ValueError(f"Expected 98 BiC entity registrations, found {len(registrations)}")
    return sorted(registrations)


def write_bic_entities(out_dir: Path, registrations) -> None:
    with (out_dir / "M0-bic-entities.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.writer(stream)
        writer.writerow(["registry_id", "entity_class", "mob_category", "use_status"])
        for registry_id, entity_class, category in registrations:
            if category == "MONSTER":
                status = "monster category; candidate only—verify AI, behavior, spawn suitability, and playtest"
            elif category == "MISC":
                status = "misc entity; likely projectile/utility; do not use as ordinary mob without review"
            else:
                status = "living non-monster category; verify behavior before considering"
            writer.writerow([registry_id, entity_class, category, status])


def rewrite_pack_hashes(
    out_dir: Path,
    mods_dir: Path,
    expected_hash_file: Path,
    allowed_extra_jars: set[str],
) -> None:
    expected: dict[str, str] = {}
    for line in expected_hash_file.read_text(encoding="utf-8").splitlines():
        parts = line.split(maxsplit=1)
        if len(parts) == 2:
            digest, name = parts[0].upper(), parts[1].strip()
            if not re.fullmatch(r"[0-9A-F]{64}", digest):
                raise ValueError(f"Invalid SHA-256 in expected pack list: {line!r}")
            if name in expected:
                raise ValueError(f"Duplicate JAR in expected pack list: {name}")
            expected[name] = digest
    if not expected:
        raise ValueError("Expected pack hash list is empty")
    missing = sorted(name for name in expected if not (mods_dir / name).is_file())
    if missing:
        raise ValueError(f"Missing pack JARs: {missing}")
    actual_jars = {jar.name for jar in mods_dir.glob("*.jar")}
    unexpected = sorted(actual_jars - expected.keys() - allowed_extra_jars)
    if unexpected:
        raise ValueError(f"Unexpected JARs in mods directory: {unexpected}")
    absent_extras = sorted(allowed_extra_jars - actual_jars)
    if absent_extras:
        raise ValueError(f"Expected audit/add-on JARs are missing: {absent_extras}")
    changed = sorted(
        name for name, digest in expected.items() if sha256(mods_dir / name) != digest
    )
    if changed:
        raise ValueError(f"Pack JAR hashes differ from expected manifest: {changed}")
    with (out_dir / "M0-server-mods.sha256").open("w", encoding="utf-8", newline="\n") as stream:
        for name in sorted(expected):
            stream.write(f"{expected[name]}  {name}\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mods-dir", type=Path, required=True)
    parser.add_argument("--runtime-tags", type=Path, required=True)
    parser.add_argument("--bic-javap", type=Path, required=True)
    parser.add_argument("--expected-pack-hashes", type=Path, required=True)
    parser.add_argument(
        "--allowed-extra-jar",
        action="append",
        default=[],
        help="Exact non-pack JAR filename allowed in the mods directory; repeat for each audit/add-on JAR.",
    )
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()
    args.out_dir.mkdir(parents=True, exist_ok=True)

    raw, rows, all_blocks, tags_by_block = read_runtime_tags(args.runtime_tags)
    blockstates, loot_tables = jar_evidence(args.mods_dir)
    outputs_by_block = {block: set(entry[2]) for block, entry in loot_tables.items()}
    candidates = registry_candidate_ids(all_blocks, tags_by_block, outputs_by_block)
    needed_jars = {
        jar
        for block in candidates
        for pair in (blockstates.get(block, ("", "")), loot_tables.get(block, ("", "", [])))
        for jar in (pair[1],)
        if jar
    }
    hashes = jar_hashes(args.mods_dir, needed_jars)
    registrations = read_bic_registration_dump(args.bic_javap)

    (args.out_dir / "M0-runtime-block-tags.tsv.gz").write_bytes(
        gzip.compress(raw, compresslevel=9, mtime=0)
    )
    write_ore_candidates(args.out_dir, candidates, tags_by_block, blockstates, loot_tables, hashes)
    write_bic_entities(args.out_dir, registrations)
    rewrite_pack_hashes(
        args.out_dir,
        args.mods_dir,
        args.expected_pack_hashes,
        set(args.allowed_extra_jar),
    )

    tag_ids = {tag for _, tag in rows[1:] if tag}
    common_ores = {
        block
        for block, tags in tags_by_block.items()
        if any(tag == "c:ores" or tag.startswith("c:ores/") for tag in tags)
    }
    categories = Counter(category for _, _, category in registrations)
    print(
        f"blocks={len(all_blocks)} tagged_blocks={len(tags_by_block)} tag_ids={len(tag_ids)} "
        f"ore_candidates={len(candidates)} common_ore_family={len(common_ores)} bic_ids={len(registrations)}"
    )
    print(f"BiC categories={dict(sorted(categories.items()))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
