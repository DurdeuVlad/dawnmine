# Runtime tag probe

This directory is frozen historical tooling for the 0.2 Public Mines audit.
Its old mod/package identifiers and command intentionally reproduce that audit;
it is not part of Dawnmine 0.3, its build, or its release artifacts.

This is an audit-only NeoForge mod. It exports the loaded block registry and each block holder's resolved tag memberships to `reports/runtime-block-tags.tsv` when an operator runs `/publicmines-audit block-tags`, and the equivalent item registry/tag dump to `reports/runtime-item-tags.tsv` with `/publicmines-audit item-tags`.

Build it from the repository root:

```powershell
.\gradlew.bat -p tools\audit\runtime-tag-probe build --console=plain
```

Install the resulting `publicmines_auditprobe` jar only in an isolated copy of the target server. Run the command from the server console after `Done`, then stop the server cleanly. The command requires permission level 2 and writes under the server working directory. Do not add this probe to the production modpack or deploy it to the live server.

The probe uses the server's loaded `BuiltInRegistries.BLOCK` holders, so its output records resolved runtime tag membership rather than simply concatenating tag JSON files. It does not decide which blocks are ores or whether a mob is hostile; those judgments belong in the reviewed audit report.

To rebuild the checked-in CSV summaries and compressed runtime snapshot after exporting the tag file and capturing the BiC `javap -v -p` output, run `tools/audit/summarize_m0.py` with those input paths, the exact server mods directory, and the existing `docs/reports/M0-server-mods.sha256` expected file list. Pass each non-pack JAR explicitly with `--allowed-extra-jar`; for the audited server those are `publicmines-0.1.0.jar` and `publicmines_auditprobe-0.1.0.jar`. The generator rejects malformed TSV, missing or hash-mismatched pack JARs, unlisted extra JARs, missing declared extra JARs, or a BiC registration count other than 98.
