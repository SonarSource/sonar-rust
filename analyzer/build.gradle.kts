
task<Exec>("compileRust") {
  description = "Compiles Rust code."
  inputs.files("src/", "Cargo.toml", "Cargo.lock")
  outputs.files("target/release/analyzer")
  commandLine("cargo", "build", "--release")
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

task<Exec>("compileCross") {
  description = "Compiles Rust code for all supported platforms."
  inputs.files("src/", "Cargo.toml", "Cargo.lock")
  outputs.files("target/x86_64-pc-windows-gnu/release/analyzer.exe")
  commandLine("cargo", "build", "--release", "--target", "x86_64-pc-windows-gnu")
}
