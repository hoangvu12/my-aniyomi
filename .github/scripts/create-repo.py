#!/usr/bin/env python3
"""
Parse APK metadata using aapt, merge with inspector output,
and generate index.json + index.min.json for the Aniyomi extension repository.
"""

import json
import re
import subprocess
from pathlib import Path

REPO_DIR = Path("repo")
APK_DIR = REPO_DIR / "apk"
INSPECTOR_OUTPUT = Path("inspector-output.json")


def load_inspector_data() -> dict:
    """Load source metadata from the extensions inspector output."""
    if not INSPECTOR_OUTPUT.exists():
        print("Warning: inspector-output.json not found, sources will be empty")
        return {}
    with open(INSPECTOR_OUTPUT, "r", encoding="utf-8") as f:
        return json.load(f)


def parse_apk(apk_path: Path, inspector_data: dict) -> dict | None:
    """Extract metadata from an APK using aapt and merge with inspector data."""
    try:
        result = subprocess.run(
            ["aapt", "dump", "badging", str(apk_path)],
            capture_output=True,
            text=True,
            timeout=30,
        )
        output = result.stdout
    except (subprocess.TimeoutExpired, FileNotFoundError):
        print(f"  Error parsing {apk_path.name}")
        return None

    pkg_match = re.search(
        r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", output
    )
    if not pkg_match:
        print(f"  Could not parse package info from {apk_path.name}")
        return None

    pkg_name = pkg_match.group(1)
    version_code = int(pkg_match.group(2))
    version_name = pkg_match.group(3)

    # App name from aapt
    app_name = ""
    name_match = re.search(r"application-label:'([^']*)'", output)
    if name_match:
        app_name = name_match.group(1)

    # NSFW flag
    nsfw = 0
    nsfw_match = re.search(
        r"meta-data: name='tachiyomi\.animeextension\.nsfw' value='(\d+)'", output
    )
    if nsfw_match:
        nsfw = int(nsfw_match.group(1))

    # Language from package name
    lang_match = re.search(
        r"eu\.kanade\.tachiyomi\.animeextension\.([a-z]+)\.", pkg_name
    )
    lang = lang_match.group(1) if lang_match else "all"

    # Sources from inspector
    sources = []
    if pkg_name in inspector_data:
        for src in inspector_data[pkg_name]:
            sources.append(
                {
                    "name": src.get("name", ""),
                    "lang": src.get("lang", lang),
                    "id": str(src.get("id", "")),
                    "baseUrl": src.get("baseUrl", ""),
                }
            )

    # If inspector didn't find the app name, use aapt label
    # If aapt label is also empty, try to derive from sources
    if not app_name and sources:
        app_name = f"Aniyomi: {sources[0]['name']}"

    return {
        "name": app_name,
        "pkg": pkg_name,
        "apk": apk_path.name,
        "lang": lang,
        "code": version_code,
        "version": version_name,
        "nsfw": nsfw,
        "sources": sources,
    }


def main():
    if not APK_DIR.exists():
        print("No APK directory found.")
        return

    inspector_data = load_inspector_data()

    extensions = []
    for apk in sorted(APK_DIR.glob("*.apk")):
        print(f"Processing: {apk.name}")
        meta = parse_apk(apk, inspector_data)
        if meta:
            extensions.append(meta)

    # Write full index (with versionId in sources)
    full_extensions = []
    for ext in extensions:
        full_ext = dict(ext)
        full_ext["sources"] = []
        for src in ext["sources"]:
            full_src = dict(src)
            if ext["pkg"] in inspector_data:
                for isrc in inspector_data[ext["pkg"]]:
                    if str(isrc.get("id", "")) == src["id"]:
                        full_src["versionId"] = isrc.get("versionId", 1)
                        break
            full_ext["sources"].append(full_src)
        full_extensions.append(full_ext)

    index_path = REPO_DIR / "index.json"
    with open(index_path, "w", encoding="utf-8") as f:
        json.dump(full_extensions, f, indent=2, ensure_ascii=False)

    # Write minified index (without versionId in sources)
    min_path = REPO_DIR / "index.min.json"
    with open(min_path, "w", encoding="utf-8") as f:
        json.dump(extensions, f, separators=(",", ":"), ensure_ascii=False)

    print(f"\nGenerated index with {len(extensions)} extension(s)")


if __name__ == "__main__":
    main()
