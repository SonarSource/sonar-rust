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

## Running End-to-End Tests

End-to-end tests verify the entire system from start to finish. These tests involve starting a SonarQube instance, invoking the scanner as a user would, running the sensor, sending issues to the Plugin API, processing them using the SonarQube instance, and finally comparing the outcome to the expected behavior.

To run the end-to-end tests, use the following command:

```
./gradlew :e2e:test -Pe2e
```

It executes the end-to-end tests, ensuring that the entire system works as expected.
