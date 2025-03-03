/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
mod analyze;
mod issue;
mod rules {
    pub mod parsing_error_check;
    pub mod rule;
}
mod tree;
mod visitors {
    pub mod cognitive_complexity;
    pub mod cpd;
    pub mod cyclomatic_complexity;
    pub mod highlight;
    pub mod metrics;
}

use analyze::analyze;
use std::io::{self, Read, Write};
use tree::SonarLocation;

fn main() {
    loop {
        let command = read_string();
        if command != "analyze" {
            return;
        }

        let len = read_i32();
        let mut buf = vec![0u8; len as usize];
        io::stdin().read_exact(&mut buf).expect("read from stdin");

        let output = analyze(std::str::from_utf8(&buf).expect("UTF-8 conversion error"));

        for token in &output.highlight_tokens {
            write_string("highlight");
            write_string(token.token_type.to_sonar_api_name());
            write_location(&token.location);
        }

        write_string("metrics");
        write_int(output.metrics.ncloc);
        write_int(output.metrics.comment_lines);
        write_int(output.metrics.functions);
        write_int(output.metrics.statements);
        write_int(output.metrics.classes);
        write_int(output.metrics.cognitive_complexity);
        write_int(output.metrics.cyclomatic_complexity);

        for token in &output.cpd_tokens {
            write_string("cpd");
            write_string(&token.image);
            write_location(&token.location);
        }

        for issue in &output.issues {
            write_string("issue");
            write_string(&issue.rule_key);
            write_string(&issue.message);
            write_location(&issue.location);
        }

        write_string("end");
    }
}

fn read_i32() -> i32 {
    // Read an i32 from stdin
    let mut buf = [0u8; 4];
    io::stdin().read_exact(&mut buf).expect("read from stdin");
    i32::from_be_bytes(buf)
}

fn read_string() -> String {
    let len = read_i32();
    let mut buf = vec![0u8; len as usize];
    io::stdin().read_exact(&mut buf).expect("read from stdin");
    String::from_utf8(buf).expect("UTF-8 conversion error")
}

fn write_int(value: i32) {
    io::stdout()
        .write_all(&value.to_be_bytes())
        .expect("write to stdout");
}

fn write_string(value: &str) {
    write_int(value.len() as i32);
    io::stdout()
        .write_all(value.as_bytes())
        .expect("write to stdout");
    io::stdout().flush().expect("flush stdout");
}

fn write_location(location: &SonarLocation) {
    write_int(location.start_line as i32);
    write_int(location.start_column as i32);
    write_int(location.end_line as i32);
    write_int(location.end_column as i32);
}
