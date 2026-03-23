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
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

CLIPPY_RULE_URL = "https://rust-lang.github.io/rust-clippy/master/index.html"
CLIPPY_RULE_LINK_RE = re.compile(r"rust-clippy/master/index\.html#([a-z0-9_]+)")
DECLARE_CLIPPY_LINT_RE = re.compile(r"\bdeclare_clippy_lint!\s*([({\[])")
PUB_LINT_RE = re.compile(r"\bpub\s+([A-Z0-9_]+)\s*,\s*([a-z_][a-z0-9_]*)\s*,")
OPEN_TO_CLOSE = {"{": "}", "(": ")", "[": "]"}
SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
DEFAULT_METADATA_OUTPUT = REPO_ROOT / "sonar-rust-plugin/src/main/resources/org/sonar/l10n/rust/rules/clippy/metadata.json"
DEFAULT_RULES_OUTPUT = REPO_ROOT / "sonar-rust-plugin/src/main/resources/org/sonar/l10n/rust/rules/clippy/rules.json"
DEFAULT_UPSTREAM_STATE = REPO_ROOT / "sonar-rust-plugin/src/main/resources/org/sonar/l10n/rust/rules/clippy/upstream.json"
DEFAULT_MESSAGES = SCRIPT_DIR / "messages.json"
DEFAULT_RULE_KEY_OVERRIDES = SCRIPT_DIR / "rule-key-overrides.json"
DEFAULT_RULE_SPECS_DIR = REPO_ROOT / "sonar-rust-plugin/src/main/resources/org/sonar/l10n/rust/rules/rust"
DEFAULT_SOURCE_REPO = "rust-lang/rust"
DEFAULT_SOURCE_PATH = "src/tools/clippy/clippy_lints/src"
GENERATOR_VERSION = "python-v1"


def json_text(data: object) -> str:
    return json.dumps(data, indent=2) + "\n"


def read_json(path: Path) -> object:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json_text(data), encoding="utf-8")


def write_github_output(path: Path | None, outputs: dict[str, str]) -> None:
    if path is None:
        return
    with path.open("a", encoding="utf-8") as handle:
        for key, value in outputs.items():
            handle.write(f"{key}={value}\n")


def load_upstream_state(path: Path) -> dict[str, object]:
    if not path.exists():
        return {
            "rust_release_tag": None,
            "rust_release_published_at": None,
            "source_repo": DEFAULT_SOURCE_REPO,
            "source_path": DEFAULT_SOURCE_PATH,
            "generator_version": GENERATOR_VERSION,
        }
    return read_json(path)


def fetch_latest_release(repo: str) -> dict[str, str]:
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repo}/releases/latest",
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": "sonar-rust-clippy-metadata-updater",
        },
    )
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        request.add_header("Authorization", f"Bearer {token}")

    try:
        with urllib.request.urlopen(request) as response:
            data = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"failed to fetch latest Rust release: {error}") from error

    tag_name = data.get("tag_name")
    published_at = data.get("published_at")
    if not tag_name:
        raise RuntimeError("latest Rust release response did not contain tag_name")
    return {"tag_name": tag_name, "published_at": published_at or ""}


def skip_string(source: str, index: int) -> int:
    index += 1
    while index < len(source):
        if source[index] == "\\":
            index += 2
            continue
        if source[index] == '"':
            return index + 1
        index += 1
    raise ValueError("unterminated string literal while parsing macro invocation")


def skip_raw_string(source: str, index: int) -> int | None:
    if source[index] != "r":
        return None

    hash_index = index + 1
    while hash_index < len(source) and source[hash_index] == "#":
        hash_index += 1

    if hash_index >= len(source) or source[hash_index] != '"':
        return None

    terminator = '"' + ("#" * (hash_index - index - 1))
    end = source.find(terminator, hash_index + 1)
    if end == -1:
        raise ValueError("unterminated raw string literal while parsing macro invocation")
    return end + len(terminator)


def skip_line_comment(source: str, index: int) -> int:
    newline = source.find("\n", index)
    return len(source) if newline == -1 else newline + 1


def skip_block_comment(source: str, index: int) -> int:
    depth = 1
    index += 2
    while index < len(source):
        if source.startswith("/*", index):
            depth += 1
            index += 2
            continue
        if source.startswith("*/", index):
            depth -= 1
            index += 2
            if depth == 0:
                return index
            continue
        if source[index] == '"':
            index = skip_string(source, index)
            continue
        raw_end = skip_raw_string(source, index)
        if raw_end is not None:
            index = raw_end
            continue
        index += 1
    raise ValueError("unterminated block comment while parsing macro invocation")


def strip_comments(source: str) -> str:
    parts: list[str] = []
    index = 0

    while index < len(source):
        if source.startswith("//", index):
            index = skip_line_comment(source, index)
            parts.append("\n")
            continue
        if source.startswith("/*", index):
            index = skip_block_comment(source, index)
            parts.append(" ")
            continue
        if source[index] == '"':
            string_end = skip_string(source, index)
            parts.append(source[index:string_end])
            index = string_end
            continue
        raw_end = skip_raw_string(source, index)
        if raw_end is not None:
            parts.append(source[index:raw_end])
            index = raw_end
            continue

        parts.append(source[index])
        index += 1

    return "".join(parts)


def find_balanced_token_tree(source: str, start_index: int) -> int:
    opener = source[start_index]
    closer = OPEN_TO_CLOSE[opener]
    stack = [closer]
    index = start_index + 1

    while index < len(source):
        if source.startswith("//", index):
            index = skip_line_comment(source, index)
            continue
        if source.startswith("/*", index):
            index = skip_block_comment(source, index)
            continue
        if source[index] == '"':
            index = skip_string(source, index)
            continue
        raw_end = skip_raw_string(source, index)
        if raw_end is not None:
            index = raw_end
            continue

        char = source[index]
        if char in OPEN_TO_CLOSE:
            stack.append(OPEN_TO_CLOSE[char])
            index += 1
            continue
        if char in ")}]":
            expected = stack.pop()
            if char != expected:
                raise ValueError(f"unexpected delimiter {char!r}, expected {expected!r}")
            if not stack:
                return index
        index += 1

    raise ValueError("unterminated macro token tree")


def collect_macro_bodies(source: str) -> list[str]:
    bodies: list[str] = []
    for match in DECLARE_CLIPPY_LINT_RE.finditer(source):
        start_index = match.start(1)
        end_index = find_balanced_token_tree(source, start_index)
        bodies.append(source[start_index + 1:end_index])
    return bodies


def parse_declared_lint(body: str) -> dict[str, str] | None:
    match = PUB_LINT_RE.search(strip_comments(body))
    if match is None:
        raise ValueError("could not find `pub <LINT>, <category>,` in declare_clippy_lint! body")

    key = match.group(1).lower()
    category = match.group(2)
    if category == "internal":
        return None

    return {
        "key": key,
        "name": key,
        "url": f"{CLIPPY_RULE_URL}#{key}",
        "description": f"Clippy lint <code>{key}</code>.",
    }


def generate_metadata(clippy_source_dir: Path) -> list[dict[str, str]]:
    lints: dict[str, dict[str, str]] = {}
    for rust_file in sorted(clippy_source_dir.rglob("*.rs")):
        source = rust_file.read_text(encoding="utf-8")
        for body in collect_macro_bodies(source):
            lint = parse_declared_lint(body)
            if lint is None:
                continue
            if lint["key"] in lints:
                raise ValueError(f"duplicate Clippy lint key {lint['key']} in {rust_file}")
            lints[lint["key"]] = lint
    return [lints[key] for key in sorted(lints)]


def load_messages(path: Path) -> dict[str, str]:
    messages = read_json(path)
    if not isinstance(messages, dict):
        raise ValueError(f"expected {path} to contain a JSON object")
    return messages


def load_rule_key_overrides(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    overrides = read_json(path)
    if not isinstance(overrides, dict):
        raise ValueError(f"expected {path} to contain a JSON object")
    return overrides


def extract_linked_lint_id(rule_html: Path) -> str:
    matches = sorted(set(CLIPPY_RULE_LINK_RE.findall(rule_html.read_text(encoding="utf-8"))))
    if len(matches) != 1:
        raise ValueError(f"{rule_html} must contain exactly one Clippy documentation link, found {matches}")
    return f"clippy::{matches[0]}"


def discover_rule_key_mapping(rule_specs_dir: Path, rule_key_overrides: dict[str, str]) -> dict[str, str]:
    lint_to_rule_key: dict[str, str] = {}
    seen_overrides: set[str] = set()

    for metadata_file in sorted(rule_specs_dir.glob("S*.json")):
        metadata = read_json(metadata_file)
        tags = metadata.get("tags") or []
        if "clippy" not in tags:
            continue

        rule_key = metadata.get("sqKey") or metadata_file.stem
        rule_html = metadata_file.with_suffix(".html")
        if not rule_html.exists():
            raise ValueError(f"missing HTML documentation for Clippy-tagged rule {rule_key}")

        lint_id = rule_key_overrides.get(rule_key, extract_linked_lint_id(rule_html))
        if rule_key in rule_key_overrides:
            seen_overrides.add(rule_key)

        previous = lint_to_rule_key.get(lint_id)
        if previous is not None and previous != rule_key:
            raise ValueError(f"Clippy lint {lint_id} is mapped to both {previous} and {rule_key}")
        lint_to_rule_key[lint_id] = rule_key

    unused_overrides = sorted(set(rule_key_overrides) - seen_overrides)
    if unused_overrides:
        raise ValueError(f"unused rule-key overrides: {unused_overrides}")

    return lint_to_rule_key


def generate_rules(
    messages_path: Path,
    rule_specs_dir: Path,
    rule_key_overrides_path: Path,
) -> tuple[list[dict[str, str | None]], dict[str, str]]:
    messages = load_messages(messages_path)
    rule_key_overrides = load_rule_key_overrides(rule_key_overrides_path)
    lint_to_rule_key = discover_rule_key_mapping(rule_specs_dir, rule_key_overrides)

    missing_messages = sorted(lint_id for lint_id in lint_to_rule_key if lint_id not in messages)
    if missing_messages:
        raise ValueError(f"missing curated messages for mapped Clippy lints: {missing_messages}")

    rules: list[dict[str, str | None]] = []
    for lint_id, message in messages.items():
        rules.append(
            {
                "lintId": lint_id,
                "ruleKey": lint_to_rule_key.get(lint_id),
                "message": message,
            }
        )
    return rules, lint_to_rule_key


def summarize_update(
    old_metadata: list[dict[str, str]],
    old_rules: list[dict[str, str | None]],
    metadata: list[dict[str, str]],
    rules: list[dict[str, str | None]],
    lint_to_rule_key: dict[str, str],
    rust_release_tag: str | None,
    rust_release_published_at: str | None,
) -> dict[str, object]:
    previous_keys = {entry["key"] for entry in old_metadata}
    new_keys = {entry["key"] for entry in metadata}
    new_upstream_lints = sorted(new_keys - previous_keys)
    new_upstream_without_mapping = [
        lint for lint in new_upstream_lints if f"clippy::{lint}" not in lint_to_rule_key
    ]
    rules_without_rule_key = sorted(
        entry["lintId"] for entry in rules if entry["ruleKey"] is None
    )

    return {
        "rust_release_tag": rust_release_tag,
        "rust_release_published_at": rust_release_published_at,
        "metadata_count_before": len(old_metadata),
        "metadata_count_after": len(metadata),
        "rules_count_before": len(old_rules),
        "rules_count_after": len(rules),
        "new_upstream_lints": new_upstream_lints,
        "new_upstream_lints_without_mapping": new_upstream_without_mapping,
        "rules_without_rule_key": rules_without_rule_key,
    }


def default_release_info(args: argparse.Namespace) -> tuple[str, str]:
    if args.release_tag_override:
        return args.release_tag_override, args.release_published_at_override or ""

    latest = fetch_latest_release(args.source_repo)
    return latest["tag_name"], latest["published_at"]


def command_check_release(args: argparse.Namespace) -> int:
    upstream_state = load_upstream_state(args.upstream_state)
    latest_tag, latest_published_at = default_release_info(args)
    current_tag = upstream_state.get("rust_release_tag")
    needs_update = current_tag != latest_tag

    result = {
        "current_release_tag": current_tag,
        "latest_release_tag": latest_tag,
        "latest_release_published_at": latest_published_at,
        "needs_update": needs_update,
    }
    print(json_text(result), end="")
    write_github_output(
        args.github_output,
        {
            "needs_update": str(needs_update).lower(),
            "release_tag": latest_tag,
            "release_published_at": latest_published_at,
        },
    )
    return 0


def command_generate_metadata(args: argparse.Namespace) -> int:
    metadata = generate_metadata(args.clippy_source_dir)
    write_json(args.output, metadata)
    print(f"wrote {len(metadata)} entries to {args.output}")
    return 0


def command_generate_rules(args: argparse.Namespace) -> int:
    rules, _ = generate_rules(args.messages_file, args.rule_specs_dir, args.rule_key_overrides_file)
    write_json(args.output, rules)
    print(f"wrote {len(rules)} entries to {args.output}")
    return 0


def command_update(args: argparse.Namespace) -> int:
    metadata = generate_metadata(args.clippy_source_dir)
    rules, lint_to_rule_key = generate_rules(
        args.messages_file,
        args.rule_specs_dir,
        args.rule_key_overrides_file,
    )

    old_metadata = read_json(args.metadata_output) if args.metadata_output.exists() else []
    old_rules = read_json(args.rules_output) if args.rules_output.exists() else []

    write_json(args.metadata_output, metadata)
    write_json(args.rules_output, rules)

    current_state = load_upstream_state(args.upstream_state)
    new_state = {
        "rust_release_tag": args.rust_release_tag or current_state.get("rust_release_tag"),
        "rust_release_published_at": args.rust_release_published_at or current_state.get("rust_release_published_at"),
        "source_repo": args.source_repo,
        "source_path": args.source_path,
        "generator_version": GENERATOR_VERSION,
    }
    write_json(args.upstream_state, new_state)

    summary = summarize_update(
        old_metadata,
        old_rules,
        metadata,
        rules,
        lint_to_rule_key,
        new_state["rust_release_tag"],
        new_state["rust_release_published_at"],
    )

    if args.summary_file is not None:
        write_json(args.summary_file, summary)
    print(json_text(summary), end="")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Maintain checked-in Clippy metadata resources.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    check_release = subparsers.add_parser(
        "check-release",
        help="Check whether upstream Rust has a newer published release.",
    )
    check_release.add_argument("--upstream-state", type=Path, default=DEFAULT_UPSTREAM_STATE)
    check_release.add_argument("--github-output", type=Path)
    check_release.add_argument("--source-repo", default=DEFAULT_SOURCE_REPO)
    check_release.add_argument("--release-tag-override")
    check_release.add_argument("--release-published-at-override")
    check_release.set_defaults(func=command_check_release)

    generate_metadata_parser = subparsers.add_parser(
        "generate-metadata",
        help="Generate the full Clippy metadata.json file from a Rust checkout.",
    )
    generate_metadata_parser.add_argument("--clippy-source-dir", type=Path, required=True)
    generate_metadata_parser.add_argument("--output", type=Path, default=DEFAULT_METADATA_OUTPUT)
    generate_metadata_parser.set_defaults(func=command_generate_metadata)

    generate_rules_parser = subparsers.add_parser(
        "generate-rules",
        help="Generate rules.json from curated messages and local Sonar rule specs.",
    )
    generate_rules_parser.add_argument("--messages-file", type=Path, default=DEFAULT_MESSAGES)
    generate_rules_parser.add_argument("--rule-key-overrides-file", type=Path, default=DEFAULT_RULE_KEY_OVERRIDES)
    generate_rules_parser.add_argument("--rule-specs-dir", type=Path, default=DEFAULT_RULE_SPECS_DIR)
    generate_rules_parser.add_argument("--output", type=Path, default=DEFAULT_RULES_OUTPUT)
    generate_rules_parser.set_defaults(func=command_generate_rules)

    update = subparsers.add_parser(
        "update",
        help="Refresh metadata.json, rules.json, and upstream.json in one step.",
    )
    update.add_argument("--clippy-source-dir", type=Path, required=True)
    update.add_argument("--metadata-output", type=Path, default=DEFAULT_METADATA_OUTPUT)
    update.add_argument("--rules-output", type=Path, default=DEFAULT_RULES_OUTPUT)
    update.add_argument("--upstream-state", type=Path, default=DEFAULT_UPSTREAM_STATE)
    update.add_argument("--messages-file", type=Path, default=DEFAULT_MESSAGES)
    update.add_argument("--rule-key-overrides-file", type=Path, default=DEFAULT_RULE_KEY_OVERRIDES)
    update.add_argument("--rule-specs-dir", type=Path, default=DEFAULT_RULE_SPECS_DIR)
    update.add_argument("--summary-file", type=Path)
    update.add_argument("--rust-release-tag")
    update.add_argument("--rust-release-published-at")
    update.add_argument("--source-repo", default=DEFAULT_SOURCE_REPO)
    update.add_argument("--source-path", default=DEFAULT_SOURCE_PATH)
    update.set_defaults(func=command_update)

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    try:
        return args.func(args)
    except Exception as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
