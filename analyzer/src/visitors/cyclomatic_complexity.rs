/*
 * SonarQube Rust Plugin
 * Copyright (C) 2025 SonarSource Sàrl
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
use crate::{
    tree::{walk_tree, AnalyzerError, NodeVisitor},
    visitors::cognitive_complexity::is_logical_operator,
};
use tree_sitter::{Node, Tree};

pub(crate) fn calculate_cyclomatic_complexity(tree: &Tree) -> Result<i32, AnalyzerError> {
    let mut visitor = CyclomaticComplexityVisitor::default();
    walk_tree(tree.root_node(), &mut visitor)?;
    Ok(visitor.complexity)
}

#[derive(Debug, Default)]
struct CyclomaticComplexityVisitor {
    complexity: i32,
}

impl NodeVisitor for CyclomaticComplexityVisitor {
    fn enter_node(&mut self, node: Node<'_>) -> Result<(), AnalyzerError> {
        match node.kind() {
            "if_expression" | "loop_expression" | "while_expression" | "for_expression"
            | "closure_expression" => {
                self.complexity += 1;
            }
            "binary_expression" if is_logical_operator(node) => {
                self.complexity += 1;
            }
            "match_arm" if has_non_empty_field(node, "value") => {
                self.complexity += 1;
            }
            "function_item" if has_non_empty_field(node, "body") => {
                self.complexity += 1;
            }
            _ => {}
        }

        Ok(())
    }
}

fn has_non_empty_field(node: Node<'_>, field_name: &str) -> bool {
    node.child_by_field_name(field_name)
        .filter(|n| {
            if n.kind() == "block" {
                n.named_child_count() > 0
            } else {
                true
            }
        })
        .is_some()
}

#[cfg(test)]
mod tests {
    use crate::tree::parse_rust_code;

    use super::calculate_cyclomatic_complexity;

    #[test]
    fn test_if() {
        assert_eq!(complexity("if x { 42 } else { 43 }"), 1);
        assert_eq!(complexity("if x { 42 } else if y { 43 } else { 44 } "), 2);
    }

    #[test]
    fn test_logic_operators() {
        // The parser needs the function body to be present, so each assertion starts with +1 complexity for the function
        assert_eq!(complexity("fn foo() { a && b }"), 2);
        assert_eq!(complexity("fn foo() { a && b && c }"), 3);
        assert_eq!(complexity("fn foo() { a && b || c }"), 3);
    }

    #[test]
    fn test_match() {
        assert_eq!(
            complexity("match x { 1 => 42, 2 => 43, 3 => 44, _ => 45 }"),
            4
        );
        assert_eq!(
            complexity(
                r#"match x { 1 => println!("one"), 2 => println!("two"), 3 => {}, _ => {} }"#
            ),
            2
        );
    }

    #[test]
    fn test_nested() {
        assert_eq!(
            complexity(
                r#"
    fn foo() { // +1
        while x { // +1
        }
        for i in 0..10 { // +1
            if i % 2 == 0 { // +1
                continue;
            } else {
                break;
            }
        }
    }"#
            ),
            4
        );
    }

    #[test]
    fn test_nested_functions_and_closures() {
        assert_eq!(
            complexity(
                r#"
    fn foo() { // +1
        fn nested(x: i32) { // +1
            if x == 2 { // +1
                4
            } else {
                5
            }
        }

        let closure = |x: i32| { // +1
            if x == 2 { // +1
                4
            } else {
                5
            }
        };
    }
        "#
            ),
            5
        );
    }

    fn complexity(source_code: &str) -> i32 {
        let tree = parse_rust_code(source_code).unwrap();
        calculate_cyclomatic_complexity(&tree).unwrap()
    }
}
