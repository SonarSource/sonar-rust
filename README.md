# sonar-rust

SonarSource Static Analyzer for Rust

## Requirements

To work on this project, you will need the following tools:

- **Java**: Programming language for developing the Sonar analyzer plugin.
- **Gradle**: Package manager for building and managing the Java projects dependencies.
- **Rust**: Programming language used for developing the native Rust analyzer.
- **Cargo**: Package manager for building and managing the Rust project and dependencies.

## Building the Project

To build the project, you can use the following Gradle commands:

- `./gradlew tasks`: Lists all the available tasks in the project.
- `./gradlew build`: Compiles the code and runs all tests.
- `./gradlew shadowJar`: Creates a fat JAR file that includes all dependencies.
- `./gradlew spotlessApply`: Formats the code according to the project's style guidelines.

### Cross-Compilation

The project includes a native Rust analyzer that needs to be cross-compiled for different platforms. To cross-compile the Rust analyzer, 
you need to install cross-compilers and Rust toolchains for the target platforms. Below are the instructions for installing the 
cross-compilers for different platforms:

#### Mac OS X

```shell
brew install SergioBenitez/osxct/x86_64-unknown-linux-gnu
brew install mingw-w64

```

#### Ubuntu

```shell
apt-get -y install rustup gcc-mingw-w64 musl-tools musl-dev build-essential autoconf libtool pkg-config
```

You also need to install the Rust toolchains for the target platforms:

```shell
rustup target add x86_64-pc-windows-gnu
rustup target add x86_64-unknown-linux-musl
rustup target add x86_64-unknown-linux-gnu
rustup target add x86_64-apple-darwin
```

To verify installed toolchains, you can run the following command:

```shell
rustup target list
```


## Running End-to-End Tests

End-to-end tests verify the entire system from start to finish. These tests involve starting a SonarQube instance, invoking the scanner as a user would, running the sensor, sending issues to the Plugin API, processing them using the SonarQube instance, and finally comparing the outcome to the expected behavior.

To run the end-to-end tests, use the following command:

```
./gradlew :e2e:test -Pe2e
```

It executes the end-to-end tests, ensuring that the entire system works as expected.
