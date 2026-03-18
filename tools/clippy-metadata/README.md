# Clippy Metadata

The `clippy-metadata` tool maintains the checked-in Clippy resources used by the plugin:

- `metadata.json`: the full upstream Clippy lint catalog used by the external rules repository
- `rules.json`: the curated Sonar subset used for rule-key and message mapping at analysis time
- `upstream.json`: the last Rust release processed by the updater

The tool is implemented in Python and intentionally has no Node.js dependency.

## Scheduled Update

The repository includes a scheduled GitHub Actions workflow at `.github/workflows/update-clippy-metadata.yml`.

That workflow:

- checks whether a newer published Rust release exists
- sparsely checks out the Clippy lint sources for that release
- regenerates `metadata.json`, `rules.json`, and `upstream.json`
- opens or updates a draft PR against `master` when the generated files change

The local CLI documented below is the implementation used by that scheduled job.

## Commands

Run the tool with:

```sh
python3 tools/clippy-metadata/clippy_metadata.py <command> [options]
```

Available commands:

- `check-release`: compare the latest Rust GitHub release with the checked-in `upstream.json`
- `generate-metadata`: parse Clippy lint declarations from a Rust checkout and write `metadata.json`
- `generate-rules`: regenerate `rules.json` from local Sonar rule specs, curated messages, and rule-key overrides
- `update`: run the full refresh and update `upstream.json`

## Typical Local Flow

```sh
python3 tools/clippy-metadata/clippy_metadata.py update \
  --clippy-source-dir /path/to/rust/src/tools/clippy/clippy_lints/src
```

This rewrites:

- `sonar-rust-plugin/src/main/resources/org/sonar/l10n/rust/rules/clippy/metadata.json`
- `sonar-rust-plugin/src/main/resources/org/sonar/l10n/rust/rules/clippy/rules.json`
- `sonar-rust-plugin/src/main/resources/org/sonar/l10n/rust/rules/clippy/upstream.json`

## Rule Generation Inputs

`rules.json` is generated from three repo-local sources:

- `messages.json`: the canonical short issue messages keyed by `lintId`
- `rule-key-overrides.json`: exceptions where the runtime lint id should not be inferred directly from the RSPEC HTML link
- `sonar-rust-plugin/src/main/resources/org/sonar/l10n/rust/rules/rust/S*.json` and matching `.html` files

The generator fails if a Clippy-tagged Sonar rule is malformed or if it has no curated message.

## Tests

```sh
python3 -m unittest discover -s tools/clippy-metadata/tests -p 'test_*.py'
```
