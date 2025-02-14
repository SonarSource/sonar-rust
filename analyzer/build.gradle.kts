
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
