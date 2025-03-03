/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */

use crate::{
    issue::Issue,
    rules::rule::Rule,
    tree::{walk_tree, NodeVisitor, SonarLocation, TreeSitterLocation},
};
use tree_sitter::{Node, Tree};

const RULE_KEY: &str = "S2260";

pub struct ParsingErrorCheck;

impl ParsingErrorCheck {
    pub fn new() -> Self {
        ParsingErrorCheck
    }
}

impl Rule for ParsingErrorCheck {
    fn key(&self) -> &str {
        RULE_KEY
    }

    fn check(&self, tree: &Tree, source_code: &str) -> Vec<Issue> {
        let mut visitor = RuleVisitor::new(source_code);
        walk_tree(tree.root_node(), &mut visitor);
        visitor.issues
    }
}

struct RuleVisitor<'a> {
    source_code: &'a str,
    issues: Vec<Issue>,
}

impl<'a> RuleVisitor<'a> {
    fn new(source_code: &'a str) -> Self {
        Self {
            source_code,
            issues: Vec::new(),
        }
    }

    fn new_issue(&mut self, message: String, location: SonarLocation) {
        self.issues.push(Issue {
            rule_key: RULE_KEY.to_string(),
            message,
            location,
        });
    }
}

impl NodeVisitor for RuleVisitor<'_> {
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

            self.new_issue(message, location);
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

            self.new_issue(message, sonar_location);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tree::parse_rust_code;

    #[test]
    fn test_compliant() {
        let source_code = r#"
fn main() {
    let x = 42;
}
"#;
        let rule = ParsingErrorCheck::new();
        let tree = parse_rust_code(source_code);

        let actual = rule.check(&tree, source_code);
        let expected = vec![];

        assert_eq!(actual, expected);
    }

    #[test]
    fn test_noncompliant() {
        let source_code = r#"
fn main() {
    let x = 42
}

fn
"#;
        let rule = ParsingErrorCheck::new();
        let tree = parse_rust_code(source_code);

        let actual = rule.check(&tree, source_code);
        let expected = vec![
            Issue {
                rule_key: rule.key().to_string(),
                message: "A syntax error occurred during parsing: missing \";\".".to_string(),
                location: SonarLocation {
                    start_line: 3,
                    start_column: 14,
                    end_line: 3,
                    end_column: 15,
                },
            },
            Issue {
                rule_key: rule.key().to_string(),
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
