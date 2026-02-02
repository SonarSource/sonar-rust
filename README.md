# Code Quality for Rust

[![Build](https://github.com/SonarSource/sonar-rust/actions/workflows/build.yml/badge.svg?branch=master)](https://github.com/SonarSource/sonar-rust/actions/workflows/build.yml) [![Quality Gate Status](https://next.sonarqube.com/sonarqube/api/project_badges/measure?project=SonarSource_sonar-rust&metric=alert_status&token=sqb_a062f88ef3aa92cecf9d7a288859fd120a839d0c)](https://next.sonarqube.com/sonarqube/dashboard?id=SonarSource_sonar-rust) [![Coverage](https://next.sonarqube.com/sonarqube/api/project_badges/measure?project=SonarSource_sonar-rust&metric=coverage&token=sqb_a062f88ef3aa92cecf9d7a288859fd120a839d0c)](https://next.sonarqube.com/sonarqube/dashboard?id=SonarSource_sonar-rust)

This SonarSource project is a code analyzer for Rust projects to help developers write [Clean Code](https://www.sonarsource.com/solutions/clean-code).

## Features

- 80+ rules
- Metrics (cognitive complexity, cyclomatic complexity, number of lines, etc.)
- Import of [test coverage reports](https://docs.sonarsource.com/sonarqube-cloud/enriching/test-coverage/overview/)
- Import of [external Clippy reports](https://docs.sonarsource.com/sonarqube-cloud/enriching/external-analyzer-reports/)

## Feedback

We welcome your feedback and feature requests to help improve the Rust analyzer. To share your thoughts or request new features, please visit the [Sonar Community Forum](https://community.sonarsource.com/). 

## Building the Project

### Requirements

To work on this project, you will need the following tools:

- Java 17
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

### Running End-to-End Tests

End-to-end tests verify the entire system from start to finish. These tests involve starting a SonarQube instance, invoking the scanner as a user would, running the sensor, sending issues to the Plugin API, processing them using the SonarQube instance, and finally comparing the outcome to the expected behavior.

To run the end-to-end tests, use the following command:

```
./gradlew :e2e:test -Pe2e
```

It executes the end-to-end tests, ensuring that the entire system works as expected.

### License File Generation

The project automatically generates and validates license files for all runtime dependencies. This ensures compliance and transparency regarding third-party licenses bundled with the plugin.

#### Java Dependencies

License files for Java runtime dependencies are managed in the `sonar-rust-plugin` module:

- **Generate licenses**: `./gradlew :sonar-rust-plugin:generateLicenseResources`
- **Validate licenses**: `./gradlew :sonar-rust-plugin:validateLicenseFiles` (runs automatically with `check`)

Generated files are stored in `sonar-rust-plugin/src/main/resources/licenses/`.

#### Rust Dependencies

License files for Rust runtime dependencies are managed in the `analyzer` module using [cargo-about](https://github.com/EmbarkStudios/cargo-about):

- **Prerequisite**: Install cargo-about with `cargo install cargo-about`
- **Generate licenses**: `./gradlew :analyzer:generateRustLicenseResources`
- **Validate licenses**: `./gradlew :analyzer:validateRustLicenseFiles` (runs automatically with `check`)

Generated files are stored in `analyzer/licenses/` and copied to each platform-specific folder during packaging.

#### Validation

Both Java and Rust license validations run as part of the `check` task. If dependencies change and license files become outdated, the build will fail with instructions to regenerate them.

# License

Copyright 2025 SonarSource.

SonarQube analyzers released after November 29, 2024, including patch fixes for prior versions,
are published under the [Sonar Source-Available License Version 1 (SSALv1)](LICENSE).

See individual files for details that specify the license applicable to each file.
Files subject to the SSALv1 will be noted in their headers.
