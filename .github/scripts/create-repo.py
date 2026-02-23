#!/usr/bin/env python3
"""
Parse APK metadata using aapt and generate index.json + index.min.json
for the Aniyomi extension repository.
"""

import json
import os
import re
import subprocess
from pathlib import Path

REPO_DIR = Path("repo")
APK_DIR = REPO_DIR / "apk"


def parse_apk(apk_path: Path) -> dict | None:
    """Extract metadata from an APK using aapt."""
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

    pkg_match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", output)
    if not pkg_match:
        print(f"  Could not parse package info from {apk_path.name}")
        return None

    pkg_name = pkg_match.group(1)
    version_code = int(pkg_match.group(2))
    version_name = pkg_match.group(3)

    app_name = ""
    name_match = re.search(r"application-label:'([^']+)'", output)
    if name_match:
        app_name = name_match.group(1)

    nsfw = 0
    nsfw_match = re.search(r"meta-data: name='tachiyomi\.animeextension\.nsfw' value='(\d+)'", output)
    if nsfw_match:
        nsfw = int(nsfw_match.group(1))

    ext_class = ""
    class_match = re.search(r"meta-data: name='tachiyomi\.animeextension\.class' value='([^']+)'", output)
    if class_match:
        ext_class = class_match.group(1)

    lang_match = re.search(r"eu\.kanade\.tachiyomi\.animeextension\.([a-z]+)\.", pkg_name)
    lang = lang_match.group(1) if lang_match else "all"

    return {
        "name": app_name,
        "pkg": pkg_name,
        "apk": apk_path.name,
        "lang": lang,
        "code": version_code,
        "version": version_name,
        "nsfw": nsfw,
        "sources": [],
        "hasChangelog": False,
    }


def main():
    if not APK_DIR.exists():
        print("No APK directory found.")
        return

    extensions = []
    for apk in sorted(APK_DIR.glob("*.apk")):
        print(f"Processing: {apk.name}")
        meta = parse_apk(apk)
        if meta:
            extensions.append(meta)

    # Write full index
    index_path = REPO_DIR / "index.json"
    with open(index_path, "w", encoding="utf-8") as f:
        json.dump(extensions, f, indent=2, ensure_ascii=False)

    # Write minified index
    min_path = REPO_DIR / "index.min.json"
    with open(min_path, "w", encoding="utf-8") as f:
        json.dump(extensions, f, separators=(",", ":"), ensure_ascii=False)

    print(f"\nGenerated index with {len(extensions)} extension(s)")


if __name__ == "__main__":
    main()
