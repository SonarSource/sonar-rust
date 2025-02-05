# Clippy Metadata

The `clippy-metadata` tool parses Clippy lint definitions and generates a JSON file containing metadata for each lint. This metadata includes the lint key and url, and a generic description.

## Prerequisites

- A recent version of Node.js installed.
- A local copy of the [rust-clippy](https://github.com/rust-lang/rust-clippy) repository.

## Usage

1. Install dependencies:
    ```sh
    npm install
    ```

2. Run the script:
    ```sh
    node index.js <path-to-clippy-lints> <output-file>
    ```

    - `<path-to-clippy-lints>`: Path to the directory containing Clippy lint source files.
    - `<output-file>`: Path to the output JSON file.

## Example

```sh
node index.js /path/to/rust-clippy/clippy_lints/src metadata.json
```

This command will parse the Clippy lint files in the `/path/to/rust-clippy/clippy_lints/src` directory and generate a `metadata.json` file with the lint metadata.

## How It Works

Here is how Clippy metadata are extracted and produced:

1. Recursively iterates over the Rust files in the folder where the Clippy lint implementations are stored.
2. Parses each Rust file using Tree-sitter.
3. Traverses the AST to find invocations of the macro `declare_clippy_lint`, which introduces a new Clippy lint.
4. Parses the Clippy lint declaration metadata to extract the relevant information:
    - Clippy rule key
    - URL to the rule description
5. Saves the extracted metadata into a JSON file.
