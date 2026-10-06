/*
 * SonarQube Rust Plugin
 * Copyright (C) SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * You can redistribute and/or modify this program under the terms of
 * the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */
mod analyze;
mod issue;
mod project;
mod recursion;
mod rules {
    pub mod cognitive_complexity_check;
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

use analyze::{analyze, analyze_file_context};
use project::Project;
use std::{
    collections::HashMap,
    io::{self, Read, Write},
};
use tree::{AnalyzerError, SonarLocation};

fn main() {
    if read_string() != "sonar" {
        return;
    }
    let parameters = read_map();

    let mut project = Project::default();
    loop {
        let command = read_string();
        if command == "project" {
            let manifests: Vec<_> = (0..read_i32()).map(|_| read_string()).collect();
            let sources = read_map();
            let (loaded, warnings) = Project::load_with_roots(&manifests, sources, |roots| {
                write_string("project-roots");
                write_int(roots.len() as i32);
                for root in roots {
                    write_string(&root.to_string_lossy());
                }
                io::stdout().flush().expect("flush project roots");
            });
            project = loaded;
            write_string("project-ready");
            write_int(warnings.len() as i32);
            for warning in warnings {
                write_string(&warning);
            }
            io::stdout().flush().expect("flush project response");
            continue;
        }
        let path = match command.as_str() {
            "analyze" => None,
            "analyze-file" | "analyze-root" => Some(read_string()),
            _ => return,
        };

        let len = read_i32();
        let mut buf = vec![0u8; len as usize];
        io::stdin().read_exact(&mut buf).expect("read from stdin");

        let source_code = std::str::from_utf8(&buf).expect("UTF-8 conversion error");

        let result = match path {
            Some(path) => analyze_file_context(
                source_code,
                &parameters,
                project.ranges(&path, source_code),
                command == "analyze-root" || project.is_root(&path),
            ),
            None => analyze(source_code, &parameters),
        };
        let output = match result {
            Ok(output) => output,
            Err(AnalyzerError::FileError(message)) => {
                eprintln!("warn {}", message);
                continue;
            }
            Err(AnalyzerError::GlobalError(message)) => {
                eprintln!("error {}", message);
                return;
            }
        };

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
            write_int(issue.secondary_locations.len() as i32);
            for secondary in &issue.secondary_locations {
                write_string(&secondary.message);
                write_location(&secondary.location);
            }
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

fn read_map() -> HashMap<String, String> {
    let mut result = HashMap::new();
    let len = read_i32();
    for _ in 0..len {
        let key: String = read_string();
        let value = read_string();
        result.insert(key, value);
    }

    result
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
