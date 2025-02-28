/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
use crate::tree::{walk_tree, NodeVisitor, SonarLocation, TreeSitterLocation};
use tree_sitter::{Node, Tree};

#[derive(Debug, PartialEq, Eq, PartialOrd, Ord, Clone)]
pub struct SyntaxError {
    pub message: String,
    pub location: SonarLocation,
}

pub fn find_syntax_errors(tree: &Tree, source_code: &str) -> Vec<SyntaxError> {
    let mut syntax_error_visitor = SyntaxErrorVisitor::new(source_code);
    walk_tree(tree.root_node(), &mut syntax_error_visitor);
    syntax_error_visitor.syntax_errors
}

#[derive(Debug)]
struct SyntaxErrorVisitor<'a> {
    source_code: &'a str,
    syntax_errors: Vec<SyntaxError>,
}

impl<'a> SyntaxErrorVisitor<'a> {
    fn new(source_code: &'a str) -> Self {
        Self {
            source_code,
            syntax_errors: Vec::new(),
        }
    }
}

impl NodeVisitor for SyntaxErrorVisitor<'_> {
    fn exit_node(&mut self, node: Node<'_>) {
        // Tree-sitter defines two types of error nodes to represent syntax errors:
        // - Error nodes: Syntax errors representing parts of the code that could not be incorporated into a valid syntax tree.
        // - Missing nodes: Missing nodes that are inserted by the parser in order to recover from certain kinds of syntax errors.

        if node.is_error() {
            // Error nodes don't include a comprehensive syntax error message and can spread over multiple nodes.
            // Tree-sitter supposedly introduced some undocumented API supposed to help in that direction, but it's low-level.
            // Therefore, we only emit a generic parsing error message until the following ticket is fixed:
            // https://github.com/tree-sitter/tree-sitter/issues/255
            let message = "A syntax error occurred during parsing.".to_string();
            let location = TreeSitterLocation::from_tree_sitter_node(node)
                .to_sonar_location(&self.source_code);

            self.syntax_errors.push(SyntaxError { message, location });
        }

        if node.is_missing() {
            // Missing nodes include a comprehensive syntax error message in the form of a S-expression.
            // The syntax of this S-expression is '(' 'MISSING' <token> ')', e.g. '(MISSING "}")'.
            let sexp = node.to_sexp();
            let error = sexp[1..sexp.len() - 1].to_string().to_lowercase();
            let message = format!("A syntax error occurred during parsing: {}.", error);

            // The Sonar location API expects the end column to be greater than the start column.
            // However, the end column of a missing node seems to be the same as the start column.
            // By precaution, we increment the end column by one to avoid potential failures.
            let location = TreeSitterLocation::from_tree_sitter_node(node)
                .to_sonar_location(&self.source_code);
            let sonar_location = SonarLocation {
                end_column: if location.start_column == location.end_column {
                    location.start_column + 1
                } else {
                    location.end_column
                },
                ..location
            };

            self.syntax_errors.push(SyntaxError {
                message,
                location: sonar_location,
            });
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tree::parse_rust_code;

    #[test]
    fn test_syntax_errors() {
        let source_code = r#"
fn main() {
    let x = 42
}

fn
"#;
        let tree = parse_rust_code(source_code);
        let actual = find_syntax_errors(&tree, source_code);
        let expected = vec![
            SyntaxError {
                message: "A syntax error occurred during parsing: missing \";\".".to_string(),
                location: SonarLocation {
                    start_line: 3,
                    start_column: 14,
                    end_line: 3,
                    end_column: 15,
                },
            },
            SyntaxError {
                message: "A syntax error occurred during parsing.".to_string(),
                location: SonarLocation {
                    start_line: 6,
                    start_column: 0,
                    end_line: 6,
                    end_column: 2,
                },
            },
        ];
        assert_eq!(actual, expected);
    }
}
