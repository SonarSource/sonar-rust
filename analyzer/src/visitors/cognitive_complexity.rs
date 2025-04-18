/*
 * SonarQube Rust Plugin
 * Copyright (C) 2025 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the Sonar Source-Available License Version 1, as published by SonarSource SA.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */
use crate::tree::{walk_tree, AnalyzerError, NodeVisitor, TreeSitterLocation};
use std::collections::HashSet;
use tree_sitter::{Node, Tree};

#[allow(dead_code)] // Location is currently only used in tests, so we allow dead code
pub struct Increment {
    pub location: TreeSitterLocation,
    pub nesting: i32,
}

pub fn calculate_total_cognitive_complexity(tree: &Tree) -> Result<i32, AnalyzerError> {
    Ok(calculate_cognitive_complexity(tree.root_node())?
        .iter()
        .map(|inc| inc.nesting + 1)
        .sum())
}

pub fn calculate_cognitive_complexity(node: Node<'_>) -> Result<Vec<Increment>, AnalyzerError> {
    let mut visitor = ComplexityVisitor::default();

    walk_tree(node, &mut visitor)?;

    Ok(visitor.current_increments)
}

#[derive(Default)]
struct ComplexityVisitor {
    current_increments: Vec<Increment>,
    visited_operators: HashSet<usize>,
    current_nesting: i32,
    current_enclosing_functions: i32,
}

impl ComplexityVisitor {
    fn increment_with_nesting(&mut self, location: Node<'_>, nesting_level: i32) {
        self.current_increments.push(Increment {
            location: TreeSitterLocation::from_tree_sitter_node(location),
            nesting: nesting_level,
        });
    }

    fn increment_without_nesting(&mut self, location: Node<'_>) {
        self.current_increments.push(Increment {
            location: TreeSitterLocation::from_tree_sitter_node(location),
            nesting: 0,
        });
    }
}

impl NodeVisitor for ComplexityVisitor {
    fn enter_node(&mut self, node: Node<'_>) -> Result<(), AnalyzerError> {
        match node.kind() {
            "function_item" => {
                if self.current_enclosing_functions > 0 {
                    self.current_nesting += 1;
                } else {
                    self.current_nesting = 0;
                }
                self.current_enclosing_functions += 1;
            }
            "if_expression" => {
                if !is_else_if(node) {
                    self.increment_with_nesting(
                        node.child(0).ok_or(AnalyzerError::FileError(
                            "an if expression must have an 'if' keyword child".to_string(),
                        ))?,
                        self.current_nesting,
                    );
                    self.current_nesting += 1;
                }
                if let Some(alternative) = node.child_by_field_name("alternative") {
                    self.increment_without_nesting(alternative.child(0).ok_or(
                        AnalyzerError::FileError(
                            "an else clause must have an 'else' keyword child".to_string(),
                        ),
                    )?);
                }
            }
            "while_expression" | "loop_expression" | "for_expression" | "match_expression" => {
                self.increment_with_nesting(
                    node.child(0).ok_or(AnalyzerError::FileError(
                        "a while/loop/for/match must have their respective keywords as a child"
                            .to_string(),
                    ))?,
                    self.current_nesting,
                );
                self.current_nesting += 1;
            }
            "label" => {
                // break and continue only increase complexity if label is used
                if let Some(parent) = node.parent() {
                    if matches!(parent.kind(), "break_expression" | "continue_expression") {
                        self.increment_without_nesting(parent);
                    }
                }
            }
            "binary_expression" if is_logical_operator(node) => {
                let operator_token =
                    node.child_by_field_name("operator")
                        .ok_or(AnalyzerError::FileError(
                            "operator must be present in binary expression".to_string(),
                        ))?;

                if self.visited_operators.contains(&operator_token.id()) {
                    return Ok(());
                }

                let mut operators = flatten_operators(node)?;
                let mut prev: Option<&str> = None;

                while let Some(operator) = operators.pop() {
                    if prev.is_none() || prev != Some(operator.kind()) {
                        self.increment_without_nesting(operator);
                    }
                    prev = Some(operator.kind());
                    self.visited_operators.insert(operator.id());
                }
            }
            "closure_expression" => {
                self.current_nesting += 1;
            }
            // TODO SKUNK-29: Check calls and handle recursion if/when we are able to reliably infer the called function
            _ => {}
        }

        Ok(())
    }

    fn exit_node(&mut self, node: Node<'_>) -> Result<(), AnalyzerError> {
        match node.kind() {
            "if_expression" => {
                if !is_else_if(node) {
                    self.current_nesting -= 1;
                }
            }
            "while_expression" | "loop_expression" | "for_expression" | "match_expression" => {
                self.current_nesting -= 1;
            }
            "function_item" => {
                self.current_enclosing_functions -= 1;
                if self.current_enclosing_functions > 0 {
                    self.current_nesting -= 1;
                }
            }
            "closure_expression" => {
                self.current_nesting -= 1;
            }
            _ => {}
        }

        Ok(())
    }
}

fn is_else_if(node: Node<'_>) -> bool {
    if let Some(parent) = node.parent() {
        if parent.kind() == "else_clause" && parent.named_child(0) == Some(node) {
            return true;
        }
    }
    false
}

pub(crate) fn is_logical_operator(node: Node<'_>) -> bool {
    if node.kind() != "binary_expression" {
        return false;
    }

    if let Some(operator) = node.child_by_field_name("operator") {
        matches!(operator.kind(), "&&" | "||")
    } else {
        false
    }
}

fn flatten_operators(node: Node<'_>) -> Result<Vec<Node<'_>>, AnalyzerError> {
    let mut operators: Vec<Node<'_>> = vec![];

    if let Some(left) = node.child_by_field_name("left") {
        if is_logical_operator(left) {
            operators.extend(flatten_operators(left)?);
        }
    }

    operators.push(
        node.child_by_field_name("operator")
            .ok_or(AnalyzerError::FileError(
                "operator must be present in a binary expression".to_string(),
            ))?,
    );

    if let Some(right) = node.child_by_field_name("right") {
        if is_logical_operator(right) {
            operators.extend(flatten_operators(right)?);
        }
    }

    Ok(operators)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tree::parse_rust_code;
    use tree_sitter::{Query, QueryCursor, StreamingIterator};

    #[derive(Debug, PartialEq)]
    struct IncrementLines {
        line: usize,
        increment: i32,
    }

    #[test]
    fn test_empty() {
        // Empty function with zero complexiy
        check_complexity("");
    }

    #[test]
    fn test_if_else() {
        check_complexity(
            "
if x { // +1
    42
}",
        );
        check_complexity(
            "
    if x { // +1 
        42
    } else { // +1
        43
    }",
        );
        check_complexity(
            "
if x { // +1
    42
} else if y { //+1
    43
}",
        );
        check_complexity(
            "
if x { // +1
    42
} else if y { // +1
    43
} else { // +1
    44
}",
        );
    }

    #[test]
    fn test_nested_else() {
        check_complexity(
            r#"
if x { // +1
    42
} else { // +1
    if y { // +2
        43
    }
}"#,
        );
        check_complexity(
            r#"
if x { // +1
    42
} else if y { // +1
    if z { // +2
        43
    } else { // +1
        44
    }
}"#,
        );
    }

    #[test]
    fn test_while() {
        check_complexity(
            r#"
while cond1 { // +1
    if cond2 { // +2
        42
    } else { // +1
        43
    }
}"#,
        );
        check_complexity(
            r#"
if x { // +1
    while y { // +2
        if z { // +3
        
        }
    }
    if z { // +2
    }
}"#,
        );
    }

    #[test]
    fn test_loop() {
        check_complexity(
            r#"
loop { // +1
    if cond { // +2
        break;
    }
}"#,
        );
    }

    #[test]
    fn test_for() {
        check_complexity(
            r#"
for x in y { // +1
    if cond { // +2
    }
}"#,
        );
    }

    #[test]
    fn test_match() {
        check_complexity(
            r#"
match x { // +1
    "a" => 1,
    "b" => 2,
    _ => 3
}        
"#,
        );
        check_complexity(
            r#"
match x { // +1
    "a" => 1,
    "b" => {
        if y { // +2
        
        } else { //+1
         
        }
    },
    _ => 3
}
"#,
        );
    }

    #[test]
    fn test_break() {
        check_complexity(
            r#"
'outer: for i in 1..=5 { // +1
    '_inner: for j in 1..=200 { // +2
        if j >= 3 { // +3
            break;
        }
        if i >= 2 { // +3
            break 'outer; // +1
        }
    }
}"#,
        );
    }

    #[test]
    fn test_continue() {
        check_complexity(
            r#"
'tens: for ten in 0..3 { // +1
    '_units: for unit in 0..=9 { // +2
        if unit % 2 == 0 { // +3
            continue;
        }
        if unit > 5 { // +3
            continue 'tens; // +1
        }
        println!("{}", ten * 10 + unit);
    }
}
"#,
        );
    }

    #[test]
    fn test_binary_operators() {
        assert_eq!(total_complexity("a && b"), 1);
        assert_eq!(total_complexity("a || b"), 1);
        assert_eq!(total_complexity("a && b && c"), 1);
        assert_eq!(total_complexity("a || b || c"), 1);
        assert_eq!(total_complexity("a || b && c"), 2);
        assert_eq!(total_complexity("a || b && c || d"), 3);
    }

    #[test]
    fn test_nested_binary_operator() {
        assert_eq!(total_complexity("if x { a && b }"), 2);
        assert_eq!(total_complexity("if x { if y && z { 42 } }"), 4);
        assert_eq!(
            total_complexity("for x in 0..5 { if y && z || a { 42 } }"),
            5
        );
    }

    #[test]
    fn test_nested_function() {
        check_complexity(
            r#"
    if x { // +1
    }
    fn nested() {
        if y { // +2
        }
    }
    if z { // +1
    }
"#,
        );
    }

    #[test]
    fn test_closures() {
        check_complexity(
            r#"
    if x { // +1
    }
    invoke(|a, b| {
        if a { // +2
        }    
    });
    if y { // +1
    }
"#,
        );
    }

    #[test]
    fn complex_nested_functions() {
        check_complexity(
            r#"
    let y = foo(x);
    if y == 0 { // +1
        fn foo(x: i32) -> i32 { // this increases nesting level
            if x > 0 { // +3
                42
            } else { // +1
                43
            }
        }

        if z == 0 { // +2
            return 42;
        }
    } else { // +1
        return 44;
    }
    return 45;
        "#,
        );
    }

    fn total_complexity(source_code: &str) -> i32 {
        let tree = parse_rust_code(format!("fn main() {{ {} }}", source_code).as_str()).unwrap();
        calculate_total_cognitive_complexity(&tree).unwrap()
    }

    fn check_complexity(source_code: &str) {
        let tree = parse_rust_code(format!("fn main() {{ {} }}", source_code).as_str()).unwrap();

        let increments = calculate_cognitive_complexity(tree.root_node()).unwrap();
        let mut expected_increments_by_line = collect_complexity_increments(source_code);

        let actual_total: i32 = increments.iter().map(|inc| inc.nesting + 1).sum();
        let expected_total: i32 = expected_increments_by_line
            .iter()
            .map(|inc| inc.increment)
            .sum();

        assert_eq!(
            actual_total, expected_total,
            "Expected total cognitive complexity to be {}",
            expected_total
        );

        let mut actual_increments_by_line = increments
            .iter()
            .map(|inc| IncrementLines {
                line: inc.location.start_position.row,
                increment: inc.nesting + 1,
            })
            .collect::<Vec<_>>();

        let sort_by_line = |a: &IncrementLines, b: &IncrementLines| a.line.cmp(&b.line);

        actual_increments_by_line.sort_by(sort_by_line);
        expected_increments_by_line.sort_by(sort_by_line);

        assert_eq!(actual_increments_by_line, expected_increments_by_line);
    }

    fn collect_complexity_increments(source_code: &str) -> Vec<IncrementLines> {
        let tree = parse_rust_code(source_code).unwrap();
        let query = Query::new(
            &tree_sitter_rust::LANGUAGE.into(),
            "(line_comment) @comment",
        )
        .expect("parse query");
        let mut cursor = QueryCursor::new();
        let mut matches = cursor.matches(&query, tree.root_node(), source_code.as_bytes());

        let mut increments = vec![];
        while let Some(m) = matches.next() {
            for capture in m.captures {
                let text = source_code[capture.node.start_byte()..capture.node.end_byte()]
                    .trim_start_matches("//")
                    .trim();
                if text.starts_with("+") {
                    text[1..]
                        .parse::<i32>()
                        .map(|increment| {
                            increments.push(IncrementLines {
                                line: capture.node.start_position().row,
                                increment,
                            })
                        })
                        .expect("parse increment");
                }
            }
        }

        increments
    }
}
