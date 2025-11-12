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
use crate::tree::{
    child_of_kind, walk_tree, AnalyzerError, NodeVisitor, SonarLocation, TreeSitterLocation,
};
use tree_sitter::Node;
use tree_sitter::Tree;

#[derive(Debug, PartialEq, Eq, PartialOrd, Ord, Clone)]
pub struct CpdToken {
    pub image: String,
    pub location: SonarLocation,
}

pub fn calculate_cpd_tokens(
    tree: &Tree,
    source_code: &str,
) -> Result<Vec<CpdToken>, AnalyzerError> {
    let mut cpd_visitor = CPDVisitor::new(source_code);
    walk_tree(tree.root_node(), &mut cpd_visitor)?;
    Ok(cpd_visitor.tokens)
}

#[derive(Debug)]
struct CPDVisitor<'a> {
    source_code: &'a str,
    tokens: Vec<CpdToken>,
    test_code_node: Option<usize>,
}

impl<'a> CPDVisitor<'a> {
    fn new(source_code: &'a str) -> Self {
        Self {
            source_code,
            tokens: Vec::new(),
            test_code_node: None,
        }
    }

    fn new_token(&mut self, image: &str, node: Node) {
        self.tokens.push(CpdToken {
            image: image.to_string(),
            location: TreeSitterLocation::from_tree_sitter_node(node)
                .to_sonar_location(self.source_code),
        });
    }
}

impl NodeVisitor for CPDVisitor<'_> {
    fn enter_node(&mut self, node: Node<'_>) -> Result<(), AnalyzerError> {
        if is_cfg_test_attribute(node, self.source_code) {
            // Ignore everything under '#[cfg(test)]' nodes as we do not want CPD on test code.
            // In the grammar, the attribute is not attached to the tree it applies to, rather it's a sibling node, so we'll look for the next sibling
            // and attach its effects there.
            if let Some(sibling) = node.next_named_sibling() {
                self.test_code_node = Some(sibling.id());
                return Ok(());
            }
        }

        if node.child_count() == 0 && self.test_code_node.is_none() {
            // Ignore source files
            // We wrongly consider them as tokens when they denote empty files
            if node.kind() == "source_file" {
                return Ok(());
            }

            // Ignore missing nodes
            // They denote syntax errors and can have identical starting and ending columns
            if node.is_missing() {
                return Ok(());
            }

            // Ignore error nodes
            // They denote syntax errors and can be unpredictable
            if node.is_error() {
                return Ok(());
            }

            // Number-like tokens
            if node.kind() == "integer_literal" || node.kind() == "float_literal" {
                self.new_token("NUMBER", node);
                return Ok(());
            }

            // String-like tokens
            if node.kind() == "string_content" {
                if let Some(parent) = node
                    .parent()
                    .filter(|parent| parent.kind() == "raw_string_literal")
                {
                    self.new_token("STRING", parent);
                } else {
                    self.new_token("STRING", node);
                }
                return Ok(());
            }

            // Default case
            let image = &self.source_code[node.start_byte()..node.end_byte()];
            self.new_token(image, node);
        }
        Ok(())
    }

    fn exit_node(&mut self, node: Node<'_>) -> Result<(), AnalyzerError> {
        if Some(node.id()) == self.test_code_node {
            self.test_code_node = None;
        }
        Ok(())
    }
}

fn is_cfg_test_attribute(node: Node<'_>, source_code: &str) -> bool {
    // '#[cfg(test)]' attributes have the following structure:
    //  (attribute_item (attribute (identifier) arguments: (token_tree (identifier))))
    if node.kind() != "attribute_item" {
        return false;
    }

    if let Some(attribute) = child_of_kind(node, "attribute") {
        let identifier = child_of_kind(attribute, "identifier")
            .map(|n| &source_code[n.start_byte()..n.end_byte()]);

        let argument = attribute
            .child_by_field_name("arguments")
            .and_then(|arg| child_of_kind(arg, "identifier"))
            .map(|n| &source_code[n.start_byte()..n.end_byte()]);

        return Some("cfg") == identifier && Some("test") == argument;
    }

    false
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tree::parse_rust_code;

    fn token(
        image: &str,
        start_line: usize,
        start_column: usize,
        end_line: usize,
        end_column: usize,
    ) -> CpdToken {
        CpdToken {
            location: SonarLocation {
                start_line,
                start_column,
                end_line,
                end_column,
            },
            image: image.to_string(),
        }
    }

    #[test]
    fn test_cpd_tokens() {
        let source_code = r#"
fn main() {
    let s = "foo";
    '"';
    42;
    3.14;
    r"
bar
";
}
"#;
        let tree = parse_rust_code(source_code).unwrap();

        let actual = calculate_cpd_tokens(&tree, source_code).unwrap();
        let expected = vec![
            token("fn", 2, 0, 2, 2),
            token("main", 2, 3, 2, 7),
            token("(", 2, 7, 2, 8),
            token(")", 2, 8, 2, 9),
            token("{", 2, 10, 2, 11),
            token("let", 3, 4, 3, 7),
            token("s", 3, 8, 3, 9),
            token("=", 3, 10, 3, 11),
            token("\"", 3, 12, 3, 13),
            token("STRING", 3, 13, 3, 16),
            token("\"", 3, 16, 3, 17),
            token(";", 3, 17, 3, 18),
            token("'\"'", 4, 4, 4, 7),
            token(";", 4, 7, 4, 8),
            token("NUMBER", 5, 4, 5, 6),
            token(";", 5, 6, 5, 7),
            token("NUMBER", 6, 4, 6, 8),
            token(";", 6, 8, 6, 9),
            token("STRING", 7, 4, 9, 1),
            token(";", 9, 1, 9, 2),
            token("}", 10, 0, 10, 1),
        ];

        assert_eq!(actual, expected);
    }

    #[test]
    fn test_empty_source() {
        let source_code = "";
        let tree = parse_rust_code(source_code).unwrap();
        let actual = calculate_cpd_tokens(&tree, source_code).unwrap();
        assert_eq!(actual, Vec::new());
    }

    #[test]
    fn test_malformed_source() {
        let source_code = r#"
fn main() {
    let s
}"#;

        let tree = parse_rust_code(source_code).unwrap();
        let actual = calculate_cpd_tokens(&tree, source_code).unwrap();
        let expected = vec![
            token("fn", 2, 0, 2, 2),
            token("main", 2, 3, 2, 7),
            token("(", 2, 7, 2, 8),
            token(")", 2, 8, 2, 9),
            token("{", 2, 10, 2, 11),
            token("let", 3, 4, 3, 7),
            token("s", 3, 8, 3, 9),
            token("}", 4, 0, 4, 1),
        ];

        assert_eq!(actual, expected);
    }

    #[test]
    fn test_cpd_is_disabled_in_tests_module() {
        let source_code = r#"
fn foo() -> String {
    return "Hello".to_string();
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn test_foo() {
        assert_eq!(foo(), "Hello");
    }
}

fn bar() {}
"#;
        let tree = parse_rust_code(source_code).unwrap();
        let actual: Vec<String> = calculate_cpd_tokens(&tree, source_code)
            .unwrap()
            .iter()
            .map(|t| t.image.clone())
            .collect();
        let expected: Vec<String> = vec![
            "fn",
            "foo",
            "(",
            ")",
            "->",
            "String",
            "{",
            "return",
            "\"",
            "STRING",
            "\"",
            ".",
            "to_string",
            "(",
            ")",
            ";",
            "}",
            "fn",
            "bar",
            "(",
            ")",
            "{",
            "}",
        ]
        .iter()
        .map(|t| t.to_string())
        .collect();

        assert_eq!(actual, expected);
    }

    #[test]
    fn test_is_cfg_test_attribute() {
        fn check(source: &str) -> bool {
            let tree = parse_rust_code(source).unwrap();
            is_cfg_test_attribute(tree.root_node().child(0).unwrap(), source)
        }

        assert_eq!(check("#[cfg(test)]"), true);
        assert_eq!(check("#[ cfg (test ) ]"), true);
        assert_eq!(check("#[cfg(abc)]"), false);
        assert_eq!(check("#[cfg(target=\"Windows\")]"), false);
        assert_eq!(check("#[cfg(not(test))]"), false);
        assert_eq!(check("#[test]"), false);
    }
}
