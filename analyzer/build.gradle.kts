import org.gradle.internal.os.OperatingSystem

fun createCompileRustTask(target: String, name: String, envVars: Map<String, String> = emptyMap()): TaskProvider<Exec> {
  return tasks.register<Exec>("compileRust$name") {
    description = "Compiles Rust code for target $target."
    group = "Rust compilation"
    inputs.files("src/", "Cargo.toml", "Cargo.lock")
    outputs.files(fileTree("target/$target/release") {
      include("analyzer", "analyzer.exe")
    })
    commandLine("cargo", "build", "--release", "--target", target)
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
val compileRustLinuxMusl = createCompileRustTask(
  "x86_64-unknown-linux-musl", "LinuxMusl",
  mapOf(
    "TARGET_CC" to "x86_64-linux-musl-gcc",
    "CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_LINKER" to "x86_64-linux-musl-gcc",
    // For some reason musl-tools don't provide ar, so we need to use the one from the gnu toolchain. Not sure if this is OK
    "AR_x86_64_unknown_linux_musl" to "x86_64-linux-gnu-ar"
  )
)
val compileRustWin = createCompileRustTask("x86_64-pc-windows-gnu", "Win")
val compileRustDarwin = createCompileRustTask("aarch64-apple-darwin", "Darwin")


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

