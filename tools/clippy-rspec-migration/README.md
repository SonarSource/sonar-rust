# Clippy RSPEC migration script

This script is used to migrate Clippy lints found in the Clippy lints source directory to Sonar RSPEC format.

## Requirements

You'll need Python3 and the dependencies listed in `requirements.txt`. You can install them using:

```bash
pip install -r requirements.txt
```

## Usage


Invoke the script with the following command:
```bash
./clippy-rspec-migration.py <path-to-clippy-lints-directory> --out <path-to-output-directory> --glean-token <GLEAN_TOKEN>
```

The LLM service used by this script is Glean. You need to provide a Glean token to authenticate with the Glean service. You can supply the token through the `--glean-token` argument or by setting the `GLEAN_TOKEN` environment variable.

### Generated files

The script generates the output files in the requested directory. In the root of the directory it creates two summary metadata files:
- `rules.json`: A JSON file containing a mapping of Clippy lint names to Sonar-style issue messages.
- `summary.json`: A JSON file containing a summary of the migration process, including the status of each lint, the raw response from the LLM and the processed response used to generate the rest of the files.

For each lint, the script generates the following files:
- `clippy.txt`: The original Clippy lint description extracted from the Clippy lints directory.
- `rule.adoc`: The generated Sonar RSPEC rule description in AsciiDoc format.
- `response.txt`: The raw response from the LLM
- `noncompliant.txt`: The output from Clippy on the noncompliant code example.
- `compliant.txt`: The output from Clippy on the compliant code example.

In addition, the `examples` directory contains Rust projects set up from the compliant/noncompliant code examples, so you can run Clippy on them to verify the lint.

### Limitations

The script currently only works with the Glean AI service. The LLM sometimes tends to ignore the output format specified in the request, so the script may fail to generate all the expected files. In this case the script will retry sending the same prompt to the LLM until it succeeds or until a maximum number of retries is reached.
