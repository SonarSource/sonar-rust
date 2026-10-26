<!-- Sonar Marketing hosts these approved brand assets on its Kentico Kontent CDN (assets-eu-01.kc-usercontent.com). Shared URLs are intentional; consult Marketing before replacing them. -->
<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://assets-eu-01.kc-usercontent.com/ef593040-b591-0198-9506-ed88b30bc023/a23fc7ba-23f0-489a-829d-ed88c0748521/Sonar_Logo_Dark%20Backgrounds.svg">
    <img src="https://assets-eu-01.kc-usercontent.com/ef593040-b591-0198-9506-ed88b30bc023/82c13eba-d95c-4bb8-8007-7ce77c14e043/Sonar_Logo_Light%20Backgrounds.svg" alt="Sonar logo" width="400">
  </picture>
</p>

[![CI](https://github.com/SonarQubeCommunity/sonar-rust/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/SonarQubeCommunity/sonar-rust/actions/workflows/ci.yml)

<!-- sonar-marketing:start -->
<!-- Marketing maintains this section. For wording changes, consult the relevant Product Marketing Manager (PMM). The repository maintainers review accuracy and merge changes. -->

# Code quality for Rust

This repository contains the SonarQube analyzer for Rust, including analysis rules, code metrics, and integrations for importing coverage and Clippy reports.

To learn more about Sonar’s Rust analysis, visit the [Sonar website](https://www.sonarsource.com/knowledge/languages/rust/).

<!-- sonar-marketing:end -->

## Features

- 80+ rules
- Metrics (cognitive complexity, cyclomatic complexity, number of lines, etc.)
- Import of [test coverage reports](https://docs.sonarsource.com/sonarqube-cloud/enriching/test-coverage/overview/)
- Import of [external Clippy reports](https://docs.sonarsource.com/sonarqube-cloud/enriching/external-analyzer-reports/)

## Community Maintenance

This analyzer was developed by SonarSource and is now maintained by the community in the [SonarQubeCommunity](https://github.com/SonarQubeCommunity) organization. It is the Rust 1.x analyzer: SonarQube Community Build bundles it in versions before 26.11. From 26.11 on, it is no longer bundled and must be installed manually.

## Installing

Download `sonar-rust-plugin-<version>.jar` from [GitHub releases](https://github.com/SonarQubeCommunity/sonar-rust/releases), copy it into your SonarQube server's `extensions/plugins` directory, and restart the server. The plugin is not available from the SonarQube Marketplace.

## Feedback and Contributions

Report bugs and request features in [GitHub issues](https://github.com/SonarQubeCommunity/sonar-rust/issues). Contributions are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md).

## Building the Project

### Requirements

To work on this project, you will need the following tools:

- Java 21
- Rust 2021
- Gradle
- Cargo

### Build

To build the project, you can use the following Gradle commands:

- `./gradlew tasks`: Lists all the available tasks in the project.
- `./gradlew build`: Compiles the code and runs all tests.
- `./gradlew shadowJar`: Creates a fat JAR file that includes all dependencies.
- `./gradlew spotlessApply`: Formats the code according to the project's style guidelines.

#### Cross-Compilation

The project includes a native Rust analyzer that needs to be cross-compiled for different platforms. To cross-compile the Rust analyzer, 
you need to install cross-compilers and Rust toolchains for the target platforms. Below are the instructions for installing the 
cross-compilers for different platforms:

##### Mac OS X

```shell
brew install SergioBenitez/osxct/x86_64-unknown-linux-gnu filosottile/musl-cross/musl-cross mingw-w64
```

##### Ubuntu

```shell
apt-get -y install rustup gcc-mingw-w64 musl-tools musl-dev build-essential autoconf libtool pkg-config
```

##### Rust Toolchains

You also need to install the Rust toolchains for the target platforms:

```shell
rustup target add x86_64-pc-windows-gnu x86_64-unknown-linux-musl x86_64-unknown-linux-gnu aarch64-unknown-linux-musl x86_64-apple-darwin
```

To verify installed toolchains, you can run the following command:

```shell
rustup target list --installed
```

### Rule Descriptions

Rule metadata is maintained by hand. To change a rule's description, title, severity, or tags, edit its files directly:

- `sonar-rust-plugin/src/main/resources/org/sonar/l10n/rust/rules/rust/S*.json` and `S*.html` for the Rust rules
- `sonar-rust-plugin/src/main/resources/org/sonar/l10n/rust/rules/clippy/` for the imported Clippy lints, regenerated with [`tools/clippy-metadata`](tools/clippy-metadata) when a new Rust release ships

### Releasing

Releases are published from GitHub:

1. Create and publish a GitHub release with a tag such as `v1.9.0`.
2. The [Release](.github/workflows/release.yml) workflow builds the native analyzers for every platform, packages the plugin with the version from the tag, and attaches the JAR and its SHA-256 checksum to the release.
3. Set `version` in `gradle.properties` to the next development version, such as `1.9.1-SNAPSHOT`.

### License File Generation

The project automatically generates and validates license files for all runtime dependencies. This ensures compliance and transparency regarding third-party licenses bundled with the plugin.

#### Java Dependencies

License files for Java runtime dependencies are managed in the `sonar-rust-plugin` module:

- **Generate licenses**: `./gradlew :sonar-rust-plugin:generateLicenseResources`
- **Validate licenses**: `./gradlew :sonar-rust-plugin:validateLicenseFiles` (runs automatically with `check`)

Generated files are stored in `sonar-rust-plugin/src/main/resources/licenses/`.

#### Rust Dependencies

License files for Rust runtime dependencies are managed in the `analyzer` module using [cargo-about](https://github.com/EmbarkStudios/cargo-about):

- **Prerequisite**: Run `mise install` to install the pinned `cargo-about` tool version
- **Generate licenses**: `./gradlew :analyzer:generateRustLicenseResources`
- **Validate licenses**: `./gradlew :analyzer:validateRustLicenseFiles` (runs automatically with `check`)

Generated files are stored in `analyzer/licenses/` and copied to each platform-specific folder during packaging.

#### Validation

Both Java and Rust license validations run as part of the `check` task. If dependencies change and license files become outdated, the build will fail with instructions to regenerate them.

# License

Copyright 2025 SonarSource.

SonarQube analyzers released after November 29, 2024, including patch fixes for prior versions,
are published under the [Sonar Source-Available License Version 1 (SSALv1)](LICENSE.txt).

See individual files for details that specify the license applicable to each file.
Files subject to the SSALv1 will be noted in their headers.
