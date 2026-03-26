#!/usr/bin/env python3
# SonarQube Rust Plugin
# Copyright (C) 2025-2026 SonarSource Sàrl
# mailto:info AT sonarsource DOT com
#
# This program is free software; you can redistribute it and/or
# modify it under the terms of the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
# See the Sonar Source-Available License for more details.
#
# You should have received a copy of the Sonar Source-Available License
# along with this program; if not, see https://sonarsource.com/license/ssal/
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def build_summary_markdown(summary: dict[str, object]) -> str:
    lines = [
        "## Clippy metadata update",
        "",
        f"- Rust release: `{summary['rust_release_tag']}`",
        f"- Published at: `{summary['rust_release_published_at']}`",
        f"- metadata.json entries: `{summary['metadata_count_before']}` -> `{summary['metadata_count_after']}`",
        f"- rules.json entries: `{summary['rules_count_before']}` -> `{summary['rules_count_after']}`",
        f"- rules without rule key: `{len(summary['rules_without_rule_key'])}`",
    ]

    new_upstream_without_mapping = summary["new_upstream_lints_without_mapping"]
    if new_upstream_without_mapping:
        lines.extend(
            [
                "",
                "### New upstream lints without Sonar mapping",
                "",
            ]
        )
        lines.extend(f"- `{lint}`" for lint in new_upstream_without_mapping)

    new_upstream_lints = summary["new_upstream_lints"]
    if new_upstream_lints:
        lines.extend(
            [
                "",
                "### New upstream lints",
                "",
            ]
        )
        lines.extend(f"- `{lint}`" for lint in new_upstream_lints)

    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Render the Clippy metadata workflow step summary.")
    parser.add_argument("--summary-file", type=Path, required=True)
    args = parser.parse_args()

    summary = json.loads(args.summary_file.read_text(encoding="utf-8"))
    sys.stdout.write(build_summary_markdown(summary))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
