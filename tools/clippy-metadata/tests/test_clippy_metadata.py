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

import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "clippy_metadata.py"
SPEC = importlib.util.spec_from_file_location("clippy_metadata_impl", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise ImportError(f"Unable to load Clippy metadata module from {MODULE_PATH}")

clippy_metadata = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(clippy_metadata)


class ClippyMetadataTest(unittest.TestCase):
    def test_generate_metadata_filters_internal_and_sorts_output(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            clippy_dir = Path(temp_dir)
            (clippy_dir / "alpha.rs").write_text(
                """
                declare_clippy_lint! {
                    /// lint docs
                    pub ZETA_LINT,
                    style,
                    "message"
                }
                """,
                encoding="utf-8",
            )
            (clippy_dir / "beta.rs").write_text(
                """
                declare_clippy_lint! {
                    pub INTERNAL_LINT,
                    internal,
                    "message"
                }

                declare_clippy_lint! {
                    pub ALPHA_LINT,
                    correctness,
                    "message"
                }
                """,
                encoding="utf-8",
            )

            metadata = clippy_metadata.generate_metadata(clippy_dir)

            self.assertEqual(
                clippy_metadata.json_text(metadata),
                """[
  {
    "key": "alpha_lint",
    "name": "alpha_lint",
    "url": "https://rust-lang.github.io/rust-clippy/master/index.html#alpha_lint",
    "description": "Clippy lint <code>alpha_lint</code>."
  },
  {
    "key": "zeta_lint",
    "name": "zeta_lint",
    "url": "https://rust-lang.github.io/rust-clippy/master/index.html#zeta_lint",
    "description": "Clippy lint <code>zeta_lint</code>."
  }
]
""",
            )

    def test_generate_rules_round_trips_checked_in_rules_json(self) -> None:
        repo_root = Path(__file__).resolve().parents[3]
        rules, lint_to_rule_key = clippy_metadata.generate_rules(
            repo_root / "tools/clippy-metadata/messages.json",
            repo_root / "sonar-rust-plugin/src/main/resources/org/sonar/l10n/rust/rules/rust",
            repo_root / "tools/clippy-metadata/rule-key-overrides.json",
        )

        expected_rules = (
            repo_root
            / "sonar-rust-plugin/src/main/resources/org/sonar/l10n/rust/rules/clippy/rules.json"
        ).read_text(encoding="utf-8")

        self.assertEqual(clippy_metadata.json_text(rules), expected_rules)
        self.assertEqual(lint_to_rule_key["clippy::never_loop"], "S1751")
        self.assertEqual(lint_to_rule_key["clippy::misnamed_getter"], "S4275")

    def test_check_release_uses_override_without_network(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            upstream_state = Path(temp_dir) / "upstream.json"
            upstream_state.write_text(
                json.dumps({"rust_release_tag": "1.80.0"}),
                encoding="utf-8",
            )

            parser = clippy_metadata.build_parser()
            args = parser.parse_args(
                [
                    "check-release",
                    "--upstream-state",
                    str(upstream_state),
                    "--release-tag-override",
                    "1.81.0",
                    "--release-published-at-override",
                    "2026-01-01T00:00:00Z",
                ]
            )

            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                self.assertEqual(clippy_metadata.command_check_release(args), 0)

            self.assertIn('"needs_update": true', stdout.getvalue())


if __name__ == "__main__":
    unittest.main()
