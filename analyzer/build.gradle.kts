import org.gradle.internal.os.OperatingSystem
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.nio.file.Files

plugins {
  base
}

buildscript {
  repositories {
    val artifactoryUsername = System.getenv("ARTIFACTORY_PRIVATE_USERNAME") ?: project.findProperty("artifactoryUsername")
    val artifactoryPassword = System.getenv("ARTIFACTORY_PRIVATE_PASSWORD") ?: project.findProperty("artifactoryPassword")
    if (artifactoryUsername is String && artifactoryPassword is String)
      maven {
        url = uri("https://repox.jfrog.io/repox/sonarsource")
        credentials {
          username = artifactoryUsername
          password = artifactoryPassword
        }
      }
    mavenCentral()
  }

  dependencies {
    classpath("org.tukaani:xz:1.10")
  }
}

val skipAnalyzerBuild = providers.gradleProperty("skipAnalyzerBuild").map {
  it.isEmpty() || it.toBoolean()
}.getOrElse(false)

val skipCrossCompile = providers.gradleProperty("skipCrossCompile").map {
  it.isEmpty() || it.toBoolean()
}.getOrElse(false)

fun createCompileRustTask(target: String, name: String, envVars: Map<String, String> = emptyMap()): TaskProvider<Exec> {
  return tasks.register<Exec>("compileRust$name") {
    description = "Compiles Rust code for target $target."
    group = "Rust compilation"
    inputs.files("src/", "Cargo.toml", "Cargo.lock")
    outputs.files(fileTree("target/$target/release") {
      include("analyzer*.xz")
    })
    commandLine("cargo", "build", "--release", "--target", target)
    doLast {
      val targetDir = project.projectDir.toPath().resolve("target/$target/release")
      val analyzer = if (target.contains("windows")) targetDir.resolve("analyzer.exe") else targetDir.resolve("analyzer")
      val analyzerXz = analyzer.resolveSibling(analyzer.fileName.toString() + ".xz")
      XZOutputStream(Files.newOutputStream(analyzerXz), LZMA2Options()).use { out -> Files.newInputStream(analyzer).use { it.copyTo(out) } }
    }

    environment(envVars)
  }
}

// Create tasks for compiling Rust code for different targets, to get a list of available targets run `rustup target list`
val muslAr = if (OperatingSystem.current().isMacOsX) {
  "x86_64-linux-musl-ar"
} else {
  "x86_64-linux-gnu-ar"
}
val compileRustLinuxMusl = createCompileRustTask(
  "x86_64-unknown-linux-musl", "LinuxMusl",
  mapOf(
    "TARGET_CC" to "x86_64-linux-musl-gcc",
    "CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_LINKER" to "x86_64-linux-musl-gcc",
    // For some reason musl-tools don't provide ar, so we need to use the one from the gnu toolchain. Not sure if this is OK
    "AR_x86_64_unknown_linux_musl" to muslAr
  )
)
val compileRustLinuxArm = if (!skipCrossCompile) {
  createCompileRustTask(
    "aarch64-unknown-linux-musl", "LinuxArm",
    mapOf(
      "TARGET_CC" to "aarch64-linux-musl-gcc",
      "CARGO_TARGET_AARCH64_UNKNOWN_LINUX_MUSL_LINKER" to "aarch64-linux-musl-gcc",
    )
  )
} else {
  null
}
val compileRustWin = if (!skipCrossCompile) {
  createCompileRustTask("x86_64-pc-windows-gnu", "Win")
} else {
  null
}
val compileRustDarwin = if (!skipCrossCompile) {
  createCompileRustTask("aarch64-apple-darwin", "Darwin")
} else {
  null
}
val compileRustDarwinX86 = if (!skipCrossCompile) {
  createCompileRustTask("x86_64-apple-darwin", "DarwinX86")
} else {
  null
}

// Connect compile tasks to assemble lifecycle (only if not skipping analyzer build)
if (!skipAnalyzerBuild) {
  tasks.assemble {
    dependsOn(compileRustLinuxMusl)
    compileRustWin?.let { dependsOn(it) }
    compileRustLinuxArm?.let { dependsOn(it) }
    if (OperatingSystem.current().isMacOsX) {
      compileRustDarwin?.let { dependsOn(it) }
      compileRustDarwinX86?.let { dependsOn(it) }
    }
  }

  // Connect testRust to check lifecycle
  tasks.check {
    dependsOn(tasks.named("testRust"))
  }
}

task<Exec>("testRust") {
  description = "Runs Rust tests."
  inputs.files("src/", "Cargo.toml", "Cargo.lock")
  outputs.files("target/release/analyzer")
  commandLine("cargo", "test", "--release")
}

task<Exec>("checkRustFormat") {
  description = "Checks Rust code formatting."
  inputs.files("src/", "Cargo.toml", "Cargo.lock")
  commandLine("cargo", "fmt", "--", "--check")
}

tasks.register("checkRustLicense") {
  description = "Checks Rust code license headers."
  group = "Verification"

  fun checkLicenseHeader(file: File, expectedHeader: String): Boolean {
    val fileContent = file.readText().trimStart()
    return fileContent.startsWith(expectedHeader)
  }

  val rustFiles = fileTree("src/") {
    include("**/*.rs")
  }.toList()

  doLast {
    val licenseHeaderFile = file("${project.rootDir}/license-header.txt")
    if (!licenseHeaderFile.exists()) {
      throw GradleException("License header file not found: ${licenseHeaderFile.path}")
    }

    val expectedHeader = licenseHeaderFile.readText().trim()

    val headerMismatches = rustFiles.filter { !checkLicenseHeader(it, expectedHeader) }
    if (!headerMismatches.isEmpty()) {
      headerMismatches.forEach { println("Missing or incorrect license header in: ${it.path}") }
      throw GradleException("Some Rust files are missing the correct license header.")
    }
  }
}

task<Exec>("coverageRust") {
  inputs.files("src/", "Cargo.toml", "Cargo.lock")
  outputs.files("target/llvm-cov-target/coverage.lcov")
  commandLine("cargo", "llvm-cov", "--lcov", "--output-path", "target/llvm-cov-target/coverage.lcov")
}

task<Exec>("clippyRust") {
  inputs.files("src/", "Cargo.toml", "Cargo.lock")
  outputs.files("clippy_report.json")
  commandLine("cargo", "clippy", "--message-format", "json")

  val outputFile = file("clippy_report.json")
  standardOutput = outputFile.outputStream()
}


if (skipAnalyzerBuild) {
  logger.lifecycle("Skipping :analyzer project (skipAnalyzerBuild=true)")
  tasks.all {
    enabled = false
  }
}
