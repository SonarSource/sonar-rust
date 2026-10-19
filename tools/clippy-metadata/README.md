# Clippy Metadata

The `clippy-metadata` tool maintains the checked-in Clippy resources used by the plugin:

- `metadata.json`: the full upstream Clippy lint catalog used by the external rules repository
- `rules.json`: the curated Sonar subset used for rule-key and message mapping at analysis time
- `upstream.json`: the last Rust release processed by the updater

The tool is implemented in Python and intentionally has no Node.js dependency.

## Updating for a New Rust Release

The update is run by hand when a new Rust release ships:

1. Check whether a newer Rust release exists:

   ```sh
   python3 tools/clippy-metadata/clippy_metadata.py check-release \
     --upstream-state sonar-rust-plugin/src/main/resources/org/sonar/l10n/rust/rules/clippy/upstream.json
   ```

2. Sparsely check out the Clippy lint sources for that release:

   ```sh
   git clone --depth 1 --filter=blob:none --branch <release-tag> https://github.com/rust-lang/rust.git /tmp/rust-source
   git -C /tmp/rust-source sparse-checkout init --cone
   git -C /tmp/rust-source sparse-checkout set src/tools/clippy/clippy_lints/src
   ```

3. Regenerate the files, passing the release tag and its publication timestamp reported by `check-release`:

   ```sh
   python3 tools/clippy-metadata/clippy_metadata.py update \
     --clippy-source-dir /tmp/rust-source/src/tools/clippy/clippy_lints/src \
     --rust-release-tag <release-tag> \
     --rust-release-published-at <published-at>
   ```

4. Review the diff, run `./gradlew build`, and open a pull request.

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
- `sonar-rust-plugin/src/test/java/org/sonarsource/rust/clippy/ClippyRulesDefinitionTest.java`

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
