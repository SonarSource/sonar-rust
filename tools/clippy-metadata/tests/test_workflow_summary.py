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

import importlib.util
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "workflow_summary.py"
SPEC = importlib.util.spec_from_file_location("workflow_summary_impl", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise ImportError(f"Unable to load workflow summary module from {MODULE_PATH}")

workflow_summary = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(workflow_summary)


class WorkflowSummaryTest(unittest.TestCase):
    def test_build_summary_markdown_formats_expected_sections(self) -> None:
        summary = {
            "rust_release_tag": "1.94.0",
            "rust_release_published_at": "2026-03-05T18:44:15Z",
            "metadata_count_before": 770,
            "metadata_count_after": 801,
            "rules_count_before": 87,
            "rules_count_after": 87,
            "new_upstream_lints": ["foo_lint", "bar_lint"],
            "new_upstream_lints_without_mapping": ["foo_lint"],
            "rules_without_rule_key": ["clippy::deprecated_semver"],
        }

        self.assertEqual(
            workflow_summary.build_summary_markdown(summary),
            """## Clippy metadata update

- Rust release: `1.94.0`
- Published at: `2026-03-05T18:44:15Z`
- metadata.json entries: `770` -> `801`
- rules.json entries: `87` -> `87`
- rules without rule key: `1`

### New upstream lints without Sonar mapping

- `foo_lint`

### New upstream lints

- `foo_lint`
- `bar_lint`
""",
        )


if __name__ == "__main__":
    unittest.main()
