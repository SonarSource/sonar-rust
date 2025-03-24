import org.gradle.internal.os.OperatingSystem
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.nio.file.Files

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
val linuxCcEnv: String = if (OperatingSystem.current().isMacOsX) {
  "x86_64-unknown-linux-gnu-gcc"
} else {
  "x86_64-linux-gnu-gcc"
}
val compileRustLinux = createCompileRustTask(
  "x86_64-unknown-linux-gnu", "Linux",
  mapOf("TARGET_CC" to linuxCcEnv, "CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER" to linuxCcEnv)
)
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
val compileRustLinuxArm = createCompileRustTask(
  "aarch64-unknown-linux-musl", "LinuxArm",
  mapOf(
    "TARGET_CC" to "aarch64-linux-musl-gcc",
    "CARGO_TARGET_AARCH64_UNKNOWN_LINUX_MUSL_LINKER" to "aarch64-linux-musl-gcc",
  )
)
val compileRustWin = createCompileRustTask("x86_64-pc-windows-gnu", "Win")
val compileRustDarwin = createCompileRustTask("aarch64-apple-darwin", "Darwin")
val compileRustDarwinX86 = createCompileRustTask("x86_64-apple-darwin", "DarwinX86")


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

