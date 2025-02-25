mod foo;

fn main() {
    if 1 == 2 || 1 < 2 { // Noncompliant: clippy::double_comparisons
        loop {}          // Noncompliant: clippy::empty_loop
    }
}
