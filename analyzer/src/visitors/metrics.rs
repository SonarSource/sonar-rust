/*
 * SonarQube Rust Plugin
 * Copyright (C) 2025-2026 SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */
use crate::tree::{walk_tree, AnalyzerError, NodeVisitor};
use crate::visitors::cognitive_complexity::calculate_total_cognitive_complexity;
use crate::visitors::cyclomatic_complexity::calculate_cyclomatic_complexity;
use std::collections::HashSet;
use tree_sitter::{Node, Tree};

#[derive(Debug, PartialEq, Eq, PartialOrd, Ord, Clone, Default)]
pub struct Metrics {
    pub ncloc: i32,
    pub comment_lines: i32,
    pub functions: i32,
    pub statements: i32,
    pub classes: i32,
    pub cognitive_complexity: i32,
    pub cyclomatic_complexity: i32,
}

pub fn calculate_metrics(tree: &Tree, source_code: &str) -> Result<Metrics, AnalyzerError> {
    let mut metrics_visitor = MetricsVisitor::new(source_code);
    walk_tree(tree.root_node(), &mut metrics_visitor)?;

    let mut metrics = Metrics::default();
    metrics_visitor.update_metrics(&mut metrics);
    metrics.cognitive_complexity = calculate_total_cognitive_complexity(tree)?;
    metrics.cyclomatic_complexity = calculate_cyclomatic_complexity(tree)?;

    Ok(metrics)
}

#[derive(Debug)]
struct MetricsVisitor<'a> {
    source_code: &'a str,
    comment_lines: HashSet<usize>,
    lines_of_code: HashSet<usize>,
    functions: i32,
    statements: i32,
    classes: i32,
}

impl<'a> MetricsVisitor<'a> {
    fn new(source_code: &'a str) -> Self {
        Self {
            source_code,
            comment_lines: HashSet::new(),
            lines_of_code: HashSet::new(),
            functions: 0,
            statements: 0,
            classes: 0,
        }
    }

    fn update_metrics(&self, metrics: &mut Metrics) {
        metrics.ncloc = self.lines_of_code.len() as i32;
        metrics.comment_lines = self.comment_lines.len() as i32;
        metrics.functions = self.functions;
        metrics.statements = self.statements;
        metrics.classes = self.classes;
    }
}

impl NodeVisitor for MetricsVisitor<'_> {
    fn exit_node(&mut self, node: Node<'_>) -> Result<(), AnalyzerError> {
        match node.kind() {
            "line_comment" | "block_comment" => {
                let mut current_line = node.start_position().row;
                for line in self.source_code[node.start_byte()..node.end_byte()].lines() {
                    if !is_blank(line) {
                        self.comment_lines.insert(current_line);
                    }
                    current_line += 1;
                }
            }
            "struct_item" | "enum_item" => {
                self.classes += 1;
            }
            "function_item" => {
                self.functions += 1;
            }
            "expression_statement" | "let_declaration" | "empty_statement" => {
                self.statements += 1;
            }
            _ => {}
        }

        if node.child_count() == 0 {
            let start_line = node.start_position().row;
            let end_line = node.end_position().row;

            for line in start_line..=end_line {
                self.lines_of_code.insert(line);
            }
        }

        Ok(())
    }
}

fn is_blank(line: &str) -> bool {
    line.chars()
        .all(|c| c.is_whitespace() || c.is_ascii_punctuation())
}

#[cfg(test)]
mod tests {

    use super::*;
    use crate::tree::parse_rust_code;

    #[test]
    fn test_comment_metrics() {
        let source_code = r#"
/* Hello */
fn main() {
    let x = "foo"; // Set 'x' to 'foo'
}         
"#;
        let tree = parse_rust_code(source_code).unwrap();
        let actual = calculate_metrics(&tree, source_code).unwrap();

        assert_eq!(
            actual,
            Metrics {
                ncloc: 3,
                comment_lines: 2,
                functions: 1,
                statements: 1,
                classes: 0,
                cognitive_complexity: 0,
                cyclomatic_complexity: 1
            }
        );
    }

    #[test]
    fn test_comment_metrics_doc_comment() {
        let source_code = r#"
/// This main function does something wonderful.
/// Even more wonderful than what you can imagine.
fn main() {
    let x = "foo";
}
"#;
        let tree = parse_rust_code(source_code).unwrap();
        let actual = calculate_metrics(&tree, source_code).unwrap();

        assert_eq!(
            actual,
            Metrics {
                ncloc: 3,
                comment_lines: 2,
                functions: 1,
                statements: 1,
                classes: 0,
                cognitive_complexity: 0,
                cyclomatic_complexity: 1
            }
        );
    }

    #[test]
    fn test_comment_metrics_empty_lines() {
        let source_code = r#"
/**
 * 
 * This is a comment
 **********************
 *
 * blabla
 */
fn main() {
    let x = "foo";
}
"#;
        let tree = parse_rust_code(source_code).unwrap();
        let actual = calculate_metrics(&tree, source_code).unwrap();

        assert_eq!(
            actual,
            Metrics {
                ncloc: 3,
                comment_lines: 2,
                functions: 1,
                statements: 1,
                classes: 0,
                cognitive_complexity: 0,
                cyclomatic_complexity: 1,
            }
        );
    }

    #[test]
    fn test_class_and_function_metrics() {
        let source_code = r#"
enum Color {
    Red,
    Green,
    Blue
}

struct Point {
    x: i32,
    y: i32,
}

impl Point {
    fn new(x: i32, y: i32) -> Point {
        Point { x, y }
    }
}

fn main() {
    let x = "foo";
    ; // Empty statement
}
"#;
        let tree = parse_rust_code(source_code).unwrap();
        let actual = calculate_metrics(&tree, source_code).unwrap();

        assert_eq!(
            actual,
            Metrics {
                ncloc: 18,
                comment_lines: 1,
                functions: 2,
                statements: 2,
                classes: 2,
                cognitive_complexity: 0,
                cyclomatic_complexity: 2
            }
        );
    }
}
