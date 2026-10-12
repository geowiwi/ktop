#!/usr/bin/env python3
"""Fill the Karoo extension manifest template for a release.

usage: generate-manifest.py <template> <output> <versionName> <versionCode> <releaseNotesFile>
"""
import json
import sys

template, output, version_name, version_code, notes_file = sys.argv[1:6]

with open(template, encoding="utf-8") as f:
    manifest = json.load(f)

try:
    with open(notes_file, encoding="utf-8") as f:
        notes = f.read().strip()
except OSError:
    notes = ""

manifest["latestVersion"] = version_name
manifest["latestVersionCode"] = int(version_code)
manifest["releaseNotes"] = notes

with open(output, "w", encoding="utf-8") as f:
    json.dump(manifest, f, indent=2, ensure_ascii=False)
    f.write("\n")
