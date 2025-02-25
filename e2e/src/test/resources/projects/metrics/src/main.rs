/**
 * #ncloc:         15
 * #classes:        2
 * #functions:      2
 * #statements:     4
 * #comment lines:  7
 */

struct Foo {
    x: i32,
}

enum Bar {
    A,
    B,
}

// Main function
fn main() {
    ;
    hello();
}

// Hello function
fn hello() {
    let message = "Hello, world!";
    println!("{}", message);
}

fn goodbye(lang: &str) {
    if lang == "en" {
        println!("Goodbye!");
    } else if lang == "es" {
        println!("Adios!");
    } else if lang == "hu" {
        println!("Viszlát!");
    } else {
        println!("Au revoir!");
    }
}
