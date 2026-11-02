#!/usr/bin/env python3
"""Extract pack recipe candidates involving ore materials or combat equipment.

This is a static resource scan, not proof that recipes are active at runtime or
that an item has particular attributes or combat behavior.
"""

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


MATERIAL_WORDS = (
    "ore", "raw_", "ingot", "nugget", "dust", "gem", "crystal", "steel",
    "silver", "iron", "diamond", "netherite", "copper", "gold", "quartz",
    "lapis", "redstone", "tin", "lead", "zinc", "nickel", "cobalt",
    "platinum", "bronze", "brass", "uranium", "osmium", "aluminum", "aluminium",
)
GEAR_WORDS = (
    "pickaxe", "axe", "sword", "shovel", "hoe", "helmet", "chestplate",
    "leggings", "boots", "armor", "armour", "shield", "staff", "wand",
    "spear", "lance", "glaive", "halberd", "mace", "hammer", "dagger",
    "flail", "bow", "crossbow", "rapier", "sabre", "saber", "scythe",
    "shortsword", "longsword", "broadsword", "bastardsword", "zweihander",
    "greatsword", "flamebladedsword", "swordbreaker", "morningstar",
)
MATERIAL_PRODUCT_WORDS = {
    "ore", "raw", "ingot", "nugget", "dust", "gem", "crystal", "block",
    "shard", "fragment", "clump", "chunk", "plate",
}
INPUT_KEYS = {
    "ingredient", "ingredients", "key", "base", "addition", "template",
    "input", "catalyst", "gem", "material", "materials", "reagent",
}


def item_path_tokens(reference: str) -> set[str]:
    value = reference.removeprefix("#").lower()
    path = value.split(":", 1)[-1]
    return set(re.findall(r"[a-z0-9]+", path))


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def load_expected(path: Path) -> dict[str, str]:
    expected: dict[str, str] = {}
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        parts = line.split(maxsplit=1)
        if len(parts) != 2:
            raise ValueError(f"Malformed expected hash line {line_number}: {line!r}")
        digest, name = parts[0].upper(), parts[1].strip()
        if not re.fullmatch(r"[0-9A-F]{64}", digest):
            raise ValueError(f"Invalid SHA-256 on expected hash line {line_number}")
        if name in expected:
            raise ValueError(f"Duplicate expected pack JAR: {name}")
        expected[name] = digest
    if not expected:
        raise ValueError("Expected pack hash list is empty")
    if len(expected) != 283:
        raise ValueError(f"Expected 283 pinned Rustic Craft 2 2.2.2 server JARs, found {len(expected)}")
    return expected


def validate_pack(mods_dir: Path, expected: dict[str, str]) -> dict[str, str]:
    missing = sorted(name for name in expected if not (mods_dir / name).is_file())
    if missing:
        raise ValueError(f"Missing expected pack JARs: {missing}")
    actual = {path.name for path in mods_dir.glob("*.jar")}
    extras = actual - expected.keys()
    allowed = {"publicmines-0.1.0.jar", "publicmines_auditprobe-0.1.0.jar"}
    if extras - allowed:
        raise ValueError(f"Unexpected JARs in mods directory: {sorted(extras - allowed)}")
    mismatch = sorted(name for name, digest in expected.items() if sha256(mods_dir / name) != digest)
    if mismatch:
        raise ValueError(f"Pack JAR SHA-256 mismatch: {mismatch}")
    return expected


def walk_refs(value, refs: set[str]) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if key in {"item", "id", "tag"} and isinstance(child, str) and ":" in child:
                refs.add(("#" if key == "tag" else "") + child)
            else:
                walk_refs(child, refs)
    elif isinstance(value, list):
        for child in value:
            walk_refs(child, refs)


def recipe_id(resource: str) -> str:
    parts = resource.split("/")
    namespace = parts[1]
    return f"{namespace}:{'/'.join(parts[3:])[:-5]}"


def classification(outputs: set[str], ingredients: set[str]) -> set[str]:
    tags = set()
    if any(
        bool(item_path_tokens(ref) & set(MATERIAL_WORDS))
        and bool(item_path_tokens(ref) & MATERIAL_PRODUCT_WORDS)
        or ref.lower().split(":", 1)[-1].endswith("_ore")
        or ref.lower().split(":", 1)[-1].startswith("raw_")
        or ref.lower().startswith(("#c:ingots/", "#c:nuggets/", "#c:gems/", "#c:ores/", "#c:raw_materials/"))
        for ref in outputs
    ):
        tags.add("material_candidate")
    if any(
        token in GEAR_WORDS
        or any(token.startswith(word) or token.endswith(word) for word in GEAR_WORDS)
        for ref in outputs
        for token in item_path_tokens(ref)
    ):
        tags.add("equipment_candidate")
    return tags


def scan_recipes(mods_dir: Path, expected: dict[str, str]):
    records = []
    malformed = []
    total_recipe_resources = 0
    for jar_name, jar_hash in sorted(expected.items()):
        with zipfile.ZipFile(mods_dir / jar_name) as archive:
            for resource in sorted(archive.namelist()):
                parts = resource.split("/")
                if (
                    len(parts) < 4
                    or parts[0] != "data"
                    or parts[2] not in {"recipe", "recipes"}
                    or not resource.endswith(".json")
                ):
                    continue
                total_recipe_resources += 1
                raw = archive.read(resource)
                try:
                    recipe = json.loads(raw)
                except (json.JSONDecodeError, UnicodeDecodeError) as error:
                    malformed.append((jar_name, resource, str(error)))
                    continue
                outputs: set[str] = set()
                walk_refs(recipe.get("result", {}), outputs)
                if isinstance(recipe.get("result"), str) and ":" in recipe["result"]:
                    outputs.add(recipe["result"])
                ingredients: set[str] = set()
                for key in INPUT_KEYS:
                    if key in recipe:
                        walk_refs(recipe[key], ingredients)
                labels = classification(outputs, ingredients)
                if not labels:
                    continue
                records.append(
                    {
                        "recipe_id": recipe_id(resource),
                        "recipe_type": recipe.get("type", ""),
                        "outputs": ";".join(sorted(outputs)),
                        "ingredients": ";".join(sorted(ingredients)),
                        "classification": ";".join(sorted(labels)),
                        "source_jar": jar_name,
                        "source_jar_sha256": jar_hash,
                        "source_resource": resource,
                        "source_resource_sha256": hashlib.sha256(raw).hexdigest().upper(),
                    }
                )
    return total_recipe_resources, records, malformed


def write_csv_gz(path: Path, records: list[dict[str, str]]) -> None:
    fields = [
        "recipe_id", "recipe_type", "outputs", "ingredients", "classification",
        "source_jar", "source_jar_sha256", "source_resource", "source_resource_sha256",
    ]
    with path.open("wb") as compressed:
        with gzip.GzipFile(fileobj=compressed, mode="wb", filename="", mtime=0, compresslevel=9) as gz:
            import io

            with io.TextIOWrapper(gz, encoding="utf-8", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=fields, lineterminator="\n")
                writer.writeheader()
                writer.writerows(records)


def write_report(path: Path, total: int, records, malformed, candidates_sha256: str) -> None:
    counts = Counter(
        label
        for record in records
        for label in record["classification"].split(";")
    )
    steel = [record for record in records if "magistuarmory:steel_ingot" in record["outputs"]]
    steel_gear = [
        record for record in records
        if "equipment_candidate" in record["classification"] and "steel" in (record["outputs"] + record["ingredients"]).lower()
    ]
    silver_gear = [
        record for record in records
        if "equipment_candidate" in record["classification"] and "silver" in (record["outputs"] + record["ingredients"]).lower()
    ]
    steel_shortsword = next((r for r in records if r["recipe_id"] == "magistuarmory:steel_shortsword"), None)
    silver_shortsword = next((r for r in records if r["recipe_id"] == "magistuarmory:silver_shortsword"), None)
    lines = [
        "# M0 static recipe and equipment candidate audit",
        "",
        "## Scope and result",
        "",
        "This is a static scan of recipe JSON resources from the pinned Rustic Craft 2 2.2.2 server mod JAR set. It identifies candidate material-processing and equipment recipes for product planning. It does not prove recipes load at runtime, item statistics, mining drops, or combat effects. It is not a complete equipment balance review.",
        "",
        f"The scan checked {total:,} recipe resources across the 283 expected pack JARs and found {len(records):,} material/equipment candidate rows. {len(malformed)} recipe resources failed JSON parsing and are excluded; they need explicit follow-up if they are relevant. The full candidate index is [M0-recipe-equipment-candidates.csv.gz](M0-recipe-equipment-candidates.csv.gz).",
        "",
        f"Candidate index SHA-256: `{candidates_sha256}`.",
        "",
        "The report generator verifies each expected pack JAR SHA-256 against [M0-server-mods.sha256](M0-server-mods.sha256), rejects missing or modified pack JARs, and rejects unexpected JARs other than the two documented audit additions.",
        "",
        "## Pack-grounded progression hooks",
        "",
        f"- Steel: {len(steel)} indexed recipe row(s) produce `magistuarmory:steel_ingot`; {len(steel_gear)} equipment candidate rows mention steel. The direct processing recipe ID is `magistuarmory:furnace/steel_ingot_blasting` in `epic-knights-1.21.1-neoforge-10.12.jar` (`data/magistuarmory/recipe/furnace/steel_ingot_blasting.json`): one `minecraft:iron_ingot` input becomes one `magistuarmory:steel_ingot` with 0.5 XP and 40-tick cook time. Steel weapon recipes can therefore support an iron-to-steel combat upgrade gate; this does not establish steel mining-tool availability or player unlock pacing.",
        (f"  The steel shortsword recipe (`magistuarmory:steel_shortsword`) uses `#c:ingots/steel` and `#magistuarmory:hilts` in `epic-knights-1.21.1-neoforge-10.12.jar` (`data/magistuarmory/recipe/steel_shortsword.json`)." if steel_shortsword else "  No steel shortsword recipe row was found by the scanner."),
        f"- Silver: {len(silver_gear)} candidate recipe row(s) mention silver in a candidate weapon/armor recipe. The silver shortsword recipe (`magistuarmory:silver_shortsword`) uses `#c:ingots/silver` and `#magistuarmory:hilts` in `epic-knights-1.21.1-neoforge-10.12.jar` (`data/magistuarmory/recipe/silver_shortsword.json`). These are candidate hooks for a silver-specific combat gate; the pack recipe data does not prove which Born in Chaos mobs require silver damage.",
        "- Ore-derived item outputs can be cross-referenced with the 29-row runtime candidate scan, but recipe tables alone do not prove that an ore block drops the listed item in gameplay.",
        "",
        "## Limits and follow-up",
        "",
        "- The scan follows standard recipe JSON resource directories and common input key names. Custom serializers may encode inputs or results differently; this index should not be treated as exhaustive for code-defined recipes.",
        "- Static recipe presence is not proof of active conditions, datapack override order, runtime recipe-book exposure, item attribute values, tool mining speed, or combat damage.",
        "- Use a disposable server to verify candidate recipe availability and native ore drops, and inspect relevant item registrations/attributes before finalizing tier rewards.",
        "- This report adds evidence to M0 but does not close M0 or replace the required gameplay and equipment review.",
        "",
        "## Reproduction",
        "",
        "```powershell",
        "python tools\\audit\\summarize_recipe_equipment.py `",
        "  --mods-dir C:\\m0s-audit\\mods `",
        "  --expected-pack-hashes docs\\reports\\M0-server-mods.sha256 `",
        "  --out-dir docs\\reports",
        "```",
        "",
    ]
    if malformed:
        lines.extend(["## Malformed resources", ""])
        for jar, resource, error in malformed[:30]:
            lines.append(f"- `{jar}` — `{resource}`: {error.splitlines()[0]}")
        if len(malformed) > 30:
            lines.append(f"- ... and {len(malformed) - 30} more; see generator output.")
        lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8", newline="\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mods-dir", type=Path, required=True)
    parser.add_argument("--expected-pack-hashes", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()
    expected = validate_pack(args.mods_dir, load_expected(args.expected_pack_hashes))
    total, records, malformed = scan_recipes(args.mods_dir, expected)
    args.out_dir.mkdir(parents=True, exist_ok=True)
    candidates_path = args.out_dir / "M0-recipe-equipment-candidates.csv.gz"
    write_csv_gz(candidates_path, records)
    write_report(args.out_dir / "M0-recipe-equipment-audit.md", total, records, malformed, sha256(candidates_path))
    print(
        f"pack_jars={len(expected)} recipe_resources={total} candidates={len(records)} "
        f"malformed={len(malformed)} classifications={dict(Counter(label for r in records for label in r['classification'].split(';')))}"
    )
    for jar, resource, error in malformed:
        print(f"MALFORMED {jar} {resource}: {error.splitlines()[0]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
