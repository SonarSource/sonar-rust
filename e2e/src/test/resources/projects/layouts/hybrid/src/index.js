const { exec } = require('child_process');

// Function to invoke the Rust project
function runRustProject() {
  exec('cargo run --manifest-path rust-crate/Cargo.toml', (error, stdout, stderr) => {
    if (error) {
      console.error(`Error executing Rust project: ${error.message}`);
      return;
    }
    if (stderr) {
      console.error(`Rust project stderr: ${stderr}`);
      return;
    }
    console.log(`Rust project output: ${stdout}`);
  });
}

// Main entry point
runRustProject();
