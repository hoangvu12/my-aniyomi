#!/usr/bin/env python3
"""
Collect built APKs from artifacts directory and move them to repo/apk/.
Strips the '-release' suffix from filenames.
"""

import os
import shutil
from pathlib import Path

ARTIFACTS_DIR = Path("artifacts")
OUTPUT_DIR = Path("repo/apk")


def main():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    apk_count = 0
    for apk in ARTIFACTS_DIR.rglob("*.apk"):
        new_name = apk.name.replace("-release", "")
        dest = OUTPUT_DIR / new_name
        shutil.copy2(apk, dest)
        apk_count += 1
        print(f"Moved: {apk.name} -> {new_name}")

    print(f"\nTotal APKs moved: {apk_count}")


if __name__ == "__main__":
    main()
