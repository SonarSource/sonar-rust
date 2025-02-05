

task<Exec>("compileRust") {
    description = "Compiles Rust code."
    inputs.files("src/", "Cargo.toml", "Cargo.lock")
    outputs.files("target/release/analyzer")
    commandLine("cargo", "build", "--release")
}
