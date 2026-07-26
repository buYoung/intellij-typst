#!/usr/bin/env python3
"""Generate the plugin's versioned native-runtime manifest from release assets."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


PLATFORMS = (
    "aarch64-apple-darwin",
    "x86_64-apple-darwin",
    "x86_64-pc-windows-msvc",
    "x86_64-unknown-linux-gnu",
)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("asset_directory", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--base-url", required=True)
    arguments = parser.parse_args()

    assets = []
    for platform in PLATFORMS:
        suffix = ".exe" if "windows" in platform else ""
        name = f"typst-runtime-{platform}{suffix}"
        path = arguments.asset_directory / name
        content = path.read_bytes()
        assets.append(
            {
                "platform": platform,
                "url": f"{arguments.base_url.rstrip('/')}/{name}",
                "size": len(content),
                "sha256": hashlib.sha256(content).hexdigest(),
            }
        )

    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(
        json.dumps({"protocolVersion": 1, "assets": assets}, indent=2) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
