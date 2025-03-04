/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
use crate::tree::{walk_tree, NodeVisitor, SonarLocation, TreeSitterLocation};
use tree_sitter::Node;
use tree_sitter::Tree;

#[derive(Debug, PartialEq, Eq, PartialOrd, Ord, Clone)]
pub struct CpdToken {
    pub image: String,
    pub location: SonarLocation,
}

pub fn calculate_cpd_tokens(tree: &Tree, source_code: &str) -> Vec<CpdToken> {
    let mut cpd_visitor = CPDVisitor::new(source_code);
    walk_tree(tree.root_node(), &mut cpd_visitor);
    cpd_visitor.tokens
}

#[derive(Debug)]
struct CPDVisitor<'a> {
    source_code: &'a str,
    tokens: Vec<CpdToken>,
    is_string_literal: bool,
}

impl<'a> CPDVisitor<'a> {
    fn new(source_code: &'a str) -> Self {
        Self {
            source_code,
            tokens: Vec::new(),
            is_string_literal: false,
        }
    }
}

impl NodeVisitor for CPDVisitor<'_> {
    fn enter_node(&mut self, node: Node<'_>) {
        if node.child_count() == 0 {
            let token_text = &self.source_code[node.start_byte()..node.end_byte()];
            let image = if token_text == "\"" {
                self.is_string_literal = !self.is_string_literal;
                token_text
            } else if token_text.parse::<f64>().is_ok() {
                "NUMBER"
            } else if self.is_string_literal {
                "STRING"
            } else {
                token_text
            };

            self.tokens.push(CpdToken {
                image: image.to_string(),
                location: TreeSitterLocation::from_tree_sitter_node(node)
                    .to_sonar_location(self.source_code),
            });
        }
    }
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
}
"#;
        let tree = parse_rust_code(source_code).unwrap();

        let actual = calculate_cpd_tokens(&tree, source_code);
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
            token("}", 6, 0, 6, 1),
        ];

        assert_eq!(actual, expected);
    }
}
