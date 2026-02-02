/*
 * SonarQube Rust Plugin
 * Copyright (C) 2025-2026 SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */
import org.sonarsource.rust.gradle.areDirectoriesEqual
import org.sonarsource.rust.gradle.copyDirectory
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * This plugin generates license files for Rust runtime dependencies using cargo-about.
 * It provides a validation task to ensure that the license files are up-to-date.
 * It provides a task to regenerate the license files.
 *
 * Prerequisites: cargo-about must be installed (cargo install cargo-about)
 */

val buildLicenseReportDirectory = project.layout.buildDirectory.dir("reports/rust-licenses")
val buildLicenseOutputDir = buildLicenseReportDirectory.map { it.dir("licenses") }
val buildThirdPartyDir = buildLicenseOutputDir.map { it.dir("THIRD_PARTY_LICENSES") }
val resourceLicenseDir = project.layout.projectDirectory.dir("licenses")
val resourceThirdPartyDir = resourceLicenseDir.dir("THIRD_PARTY_LICENSES")

tasks.register<Exec>("generateRustLicenseReport") {
    description = "Generates license report for Rust dependencies using cargo-about"
    group = "license"

    inputs.files("Cargo.toml", "Cargo.lock", "about.toml", "about.hbs")
    outputs.dir(buildLicenseReportDirectory)

    workingDir = project.projectDir
    commandLine("cargo", "about", "generate", "about.hbs", "-o", buildLicenseReportDirectory.get().asFile.resolve("raw-licenses.txt").absolutePath)

    doFirst {
        buildLicenseReportDirectory.get().asFile.deleteRecursively()
        Files.createDirectories(buildThirdPartyDir.get().asFile.toPath())
    }

    doLast {
        // Parse the raw output and split into individual license files
        val rawLicensesFile = buildLicenseReportDirectory.get().asFile.resolve("raw-licenses.txt")
        if (rawLicensesFile.exists()) {
            val content = rawLicensesFile.readText()
            val pattern = Regex("""---LICENSE_FILE_START:(.+?)---\s*([\s\S]*?)\s*---LICENSE_FILE_END---""")

            pattern.findAll(content).forEach { match ->
                val fileName = match.groupValues[1].trim()
                // Skip the analyzer crate itself - it's the project, not a dependency
                if (fileName == "analyzer-LICENSE.txt") {
                    return@forEach
                }
                val licenseText = match.groupValues[2].trim()
                val outputFile = buildThirdPartyDir.get().asFile.resolve(fileName)
                outputFile.writeText(licenseText)
                logger.lifecycle("Generated license file: $fileName")
            }
        }
    }
}

tasks.register("generateRustLicenseResources") {
    description = "Copies generated Rust license files to the resources directory"
    group = "license"
    dependsOn("generateRustLicenseReport")

    doLast {
        // Copy project license
        val projectLicenseFile = project.layout.projectDirectory.asFile.parentFile.resolve("LICENSE.txt")
        Files.createDirectories(resourceLicenseDir.asFile.toPath())
        Files.copy(
            projectLicenseFile.toPath(),
            resourceLicenseDir.file("LICENSE.txt").asFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
        logger.lifecycle("Copied project LICENSE.txt")

        // Copy third-party licenses
        copyDirectory(buildThirdPartyDir.get().asFile, resourceThirdPartyDir.asFile, logger)
    }
}

tasks.register("validateRustLicenseFiles") {
    description = "Validate that generated Rust license files match the committed ones"
    group = "verification"
    dependsOn("generateRustLicenseReport")

    doLast {
        if (!areDirectoriesEqual(buildThirdPartyDir.get().asFile, resourceThirdPartyDir.asFile, logger)) {
            val message = """
                [FAILURE] Rust license file validation failed!
                Generated license files differ from committed files at $resourceThirdPartyDir.
                To update the committed license files, run './gradlew :analyzer:generateRustLicenseResources' and commit the changes.

                Note: This will completely regenerate all license files under $resourceThirdPartyDir and remove any stale ones.
            """.trimIndent()
            throw GradleException(message)
        } else {
            logger.lifecycle("Rust license file validation succeeded: Generated license files match the committed ones.")
        }
    }
}

tasks.named("check") {
    dependsOn("validateRustLicenseFiles")
}
