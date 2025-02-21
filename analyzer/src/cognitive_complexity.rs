/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
use crate::tree::{walk_tree, NodeVisitor, TreeSitterLocation};
use std::collections::HashSet;
use tree_sitter::{Node, Tree};

#[allow(dead_code)] // Location is currently only used in tests, so we allow dead code
pub(crate) struct Increment {
    location: TreeSitterLocation,
    nesting: i32,
}

pub(crate) fn calculate_total_cognitive_complexity(tree: &Tree) -> i32 {
    calculate_cognitive_complexity(tree)
        .iter()
        .map(|inc| inc.nesting + 1)
        .sum()
}

pub(crate) fn calculate_cognitive_complexity(tree: &Tree) -> Vec<Increment> {
    let mut visitor = ComplexityVisitor::default();

    walk_tree(tree.root_node(), &mut visitor);

    visitor.current_increments
}

#[derive(Default)]
struct ComplexityVisitor {
    current_increments: Vec<Increment>,
    visited_operators: HashSet<usize>,
    current_nesting: i32,
}

impl ComplexityVisitor {
    fn increment_with_nesting(&mut self, node: Node<'_>, nesting_level: i32) {
        self.current_increments.push(Increment {
            location: TreeSitterLocation::from_tree_sitter_node(node),
            nesting: nesting_level,
        });
    }

    fn increment_without_nesting(&mut self, node: Node<'_>) {
        self.current_increments.push(Increment {
            location: TreeSitterLocation::from_tree_sitter_node(node),
            nesting: 0,
        });
    }
}

impl NodeVisitor for ComplexityVisitor {
    fn enter_node(&mut self, node: Node<'_>) {
        match node.kind() {
            "function_item" => self.current_nesting = 0,
            "if_expression" => {
                if !is_else_if(node) {
                    self.increment_with_nesting(node, self.current_nesting);
                    self.current_nesting += 1;
                }
                if let Some(alternative) = node.child_by_field_name("alternative") {
                    self.increment_without_nesting(alternative);
                }
            }
            "while_expression" | "loop_expression" | "for_expression" | "match_expression" => {
                self.increment_with_nesting(node, self.current_nesting);
                self.current_nesting += 1;
            }
            "label" => {
                // break and continue only increase complexity if label is used
                match node.parent().map(|n| n.kind()) {
                    Some("break_expression") | Some("continue_expression") => {
                        self.increment_without_nesting(node)
                    }
                    _ => {}
                }
            }
            "binary_expression" if is_logical_operator(node) => {
                let operator_token = node
                    .child_by_field_name("operator")
                    .expect("operator must be present in a binary expression");

                if self.visited_operators.contains(&operator_token.id()) {
                    return;
                }

                let mut operators = flatten_operators(node);
                let mut prev: Option<&str> = None;

                while let Some(operator) = operators.pop() {
                    if prev.is_none() || prev != Some(operator.kind()) {
                        self.increment_without_nesting(operator);
                    }
                    prev = Some(operator.kind());
                    self.visited_operators.insert(operator.id());
                }
            }
            _ => {}
        }
    }

    fn exit_node(&mut self, node: Node<'_>) {
        match node.kind() {
            "if_expression" => {
                if !is_else_if(node) {
                    self.current_nesting -= 1;
                }
            }
            "while_expression" | "loop_expression" | "for_expression" | "match_expression" => {
                self.current_nesting -= 1;
            }
            _ => {}
        }
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

fn is_logical_operator(node: Node<'_>) -> bool {
    if node.kind() != "binary_expression" {
        return false;
    }

    if let Some(operator) = node.child_by_field_name("operator") {
        matches!(operator.kind(), "&&" | "||")
    } else {
        false
    }
}

fn flatten_operators(node: Node<'_>) -> Vec<Node<'_>> {
    let mut operators = vec![];

    if let Some(left) = node.child_by_field_name("left") {
        if is_logical_operator(left) {
            operators.extend(flatten_operators(left));
        }
    }

    operators.push(
        node.child_by_field_name("operator")
            .expect("operator must be present in a binary expression"),
    );

    if let Some(right) = node.child_by_field_name("right") {
        if is_logical_operator(right) {
            operators.extend(flatten_operators(right));
        }
    }

    operators
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

    fn total_complexity(source_code: &str) -> i32 {
        let tree = parse_rust_code(format!("fn main() {{ {} }}", source_code).as_str());
        calculate_total_cognitive_complexity(&tree)
    }

    fn check_complexity(source_code: &str) {
        let tree = parse_rust_code(format!("fn main() {{ {} }}", source_code).as_str());

        let increments = calculate_cognitive_complexity(&tree);
        let expected_increments_by_line = collect_complexity_increments(source_code);

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

        let actual_increments_by_line = increments
            .iter()
            .map(|inc| IncrementLines {
                line: inc.location.start_position.row,
                increment: inc.nesting + 1,
            })
            .collect::<Vec<_>>();

        assert_eq!(actual_increments_by_line, expected_increments_by_line);
    }

    fn collect_complexity_increments(source_code: &str) -> Vec<IncrementLines> {
        let tree = parse_rust_code(source_code);
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
