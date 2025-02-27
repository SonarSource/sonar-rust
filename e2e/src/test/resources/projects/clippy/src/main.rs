fn main() {
    if 1 == 2 || 1 < 2 { // Noncompliant: clippy::double_comparisons
        loop {}          // Noncompliant: clippy::empty_loop
    }
}

fn foo() {
  if 100 > i32::MAX {}  // Noncompliant S2198
}
