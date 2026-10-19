plugins {
  base
  id("org.sonarqube") version "7.5.0.8588"
}

allprojects {
  dependencyLocking {
    lockAllConfigurations()
  }
}

// Replaces the version defined in sources, usually x.y-SNAPSHOT, by a version identifying the build.
val buildNumber: String? = System.getProperty("buildNumber")
project.ext["buildNumber"] = buildNumber
val unqualifiedVersion = project.version
if (project.version.toString().endsWith("-SNAPSHOT") && buildNumber != null) {
  val versionSuffix = if (project.version.toString().count { it == '.' } == 1) ".0.$buildNumber" else ".$buildNumber"
  project.version = project.version.toString().replace("-SNAPSHOT", versionSuffix)
}

repositories {
  mavenLocal()
  mavenCentral()
}

val checkLicenseHeaders = tasks.register("checkLicenseHeaders") {
  description = "Checks repo-authored source file license headers."
  group = "Verification"

  val licenseHeaderFile = rootProject.file("license-header.txt")
  val sourceFiles = files(
    fileTree("analyzer/src") {
      include("**/*.rs")
    },
    fileTree("sonar-rust-plugin/src") {
      include("**/*.java")
    },
    fileTree("buildSrc/src/main/kotlin") {
      include("**/*.kt", "**/*.kts")
    }
  )

  inputs.file(licenseHeaderFile)
  inputs.files(sourceFiles)

  doLast {
    val expectedHeader = licenseHeaderFile.readText().trim()
    val headerMismatches = sourceFiles.files.filter { file ->
      !file.readText().trimStart().startsWith(expectedHeader)
    }

    if (headerMismatches.isNotEmpty()) {
      headerMismatches.forEach { println("Missing or incorrect license header in: ${it.path}") }
      throw GradleException("Some source files are missing the correct license header.")
    }
  }
}

subprojects {
  tasks.matching { it.name == "check" }.configureEach {
    dependsOn(checkLicenseHeaders)
  }
}

sonar {
  properties {
    property("sonar.projectName", "sonar-rust")
    property("sonar.projectKey", "SonarSource_sonar-rust")
    property("sonar.organization", "sonarsource")
    property("sonar.gradle.scanAll", "true")
    property("sonar.exclusions", "tools,**/build/**/*")
    property("sonar.links.ci", "https://github.com/SonarQubeCommunity/sonar-rust/actions")
    property("sonar.links.scm", "https://github.com/SonarQubeCommunity/sonar-rust")
    property("sonar.links.issue", "https://github.com/SonarQubeCommunity/sonar-rust/issues")
    property("sonar.rust.lcov.reportPaths", "analyzer/target/llvm-cov-target/coverage.lcov")
    property("sonar.rust.cargo.manifestPaths", "analyzer/Cargo.toml")
    property("sonar.coverage.exclusions", "tools/**/*")
  }
}

tasks.sonar {
  dependsOn(":analyzer:coverageRust")
}
