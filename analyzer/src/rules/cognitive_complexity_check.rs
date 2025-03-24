/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
use crate::{
    issue::{Issue, SecondaryLocation},
    rules::rule::Rule,
    tree::{AnalyzerError, NodeIterator, TreeSitterLocation},
    visitors::cognitive_complexity::calculate_cognitive_complexity,
};
use tree_sitter::{Node, Tree};

const RULE_KEY: &str = "S3776";

pub struct CognitiveComplexityCheck {
    threshold: i32,
}

impl CognitiveComplexityCheck {
    pub fn new(threshold: i32) -> Self {
        CognitiveComplexityCheck { threshold }
    }
}

impl Rule for CognitiveComplexityCheck {
    fn check(&self, tree: &Tree, source_code: &str) -> Result<Vec<Issue>, AnalyzerError> {
        let iter = NodeIterator::new(tree.root_node(), |node| is_outer_function_node(node));
        let mut issues: Vec<Issue> = vec![];

        for function_item in iter {
            let increments = calculate_cognitive_complexity(function_item)?;
            let total: i32 = increments.iter().map(|inc| inc.nesting + 1).sum();

            if total > self.threshold {
                let secondary_locations: Vec<SecondaryLocation> = increments
                    .iter()
                    .map(|inc| SecondaryLocation {
                        location: inc.location.to_sonar_location(source_code),
                        message: if inc.nesting == 0 {
                            format!("+{}", inc.nesting + 1)
                        } else {
                            format!("+{} (incl {} for nesting)", inc.nesting + 1, inc.nesting)
                        },
                    })
                    .collect();

                let location =
                    function_item
                        .child_by_field_name("name")
                        .ok_or(AnalyzerError::FileError(
                            "A function_item node should have a 'name' field".to_string(),
                        ))?;

                issues.push(Issue {
                    rule_key: RULE_KEY.to_string(),
                    message: format!("Refactor this function to reduce its Cognitive Complexity from {} to the {} allowed.", total, self.threshold),
                    location: TreeSitterLocation::from_tree_sitter_node(location).to_sonar_location(source_code),
                    secondary_locations
                });
            }
        }

        Ok(issues)
    }
}

fn is_outer_function_node(node: Node<'_>) -> bool {
    if node.kind() != "function_item" {
        return false;
    }

    let mut parent = node.parent();
    while let Some(node_parent) = parent {
        if node_parent.kind() == "function_item" {
            return false;
        }

        parent = node_parent.parent();
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tree::{parse_rust_code, SonarLocation};

    #[test]
    fn test_zero_complexity() {
        let source_code = r#"
fn main() {
}
"#;
        let rule = CognitiveComplexityCheck::new(0);
        let tree = parse_rust_code(source_code).unwrap();

        let actual = rule.check(&tree, source_code).unwrap();
        let expected = vec![];

        assert_eq!(actual, expected);
    }

    #[test]
    fn if_else_complexity() {
        let source_code = r#"
fn foo(c1: bool, c2: bool) {
    if c1 { // +1
        if c2 { // +2 (incl 1 for nesting)
        
        } else { // +1
         
        }
    } else { // +1
    }
}
"#;
        let rule = CognitiveComplexityCheck::new(0);
        let tree = parse_rust_code(source_code).unwrap();

        let actual = rule.check(&tree, source_code).unwrap();

        assert_eq!(actual.len(), 1);
        assert_eq!(actual[0].rule_key, RULE_KEY);
        assert_eq!(
            actual[0].message,
            "Refactor this function to reduce its Cognitive Complexity from 5 to the 0 allowed."
        );
        assert_eq!(
            actual[0].location,
            SonarLocation {
                start_line: 2,
                start_column: 3,
                end_line: 2,
                end_column: 6
            }
        );

        assert_eq!(actual[0].secondary_locations.len(), 4);

        assert_eq!(
            actual[0].secondary_locations,
            vec![
                SecondaryLocation {
                    message: "+1".to_owned(),
                    location: SonarLocation {
                        start_line: 3,
                        start_column: 4,
                        end_line: 3,
                        end_column: 6
                    }
                },
                SecondaryLocation {
                    message: "+1".to_owned(),
                    location: SonarLocation {
                        start_line: 9,
                        start_column: 6,
                        end_line: 9,
                        end_column: 10
                    }
                },
                SecondaryLocation {
                    message: "+2 (incl 1 for nesting)".to_owned(),
                    location: SonarLocation {
                        start_line: 4,
                        start_column: 8,
                        end_line: 4,
                        end_column: 10
                    }
                },
                SecondaryLocation {
                    message: "+1".to_owned(),
                    location: SonarLocation {
                        start_line: 6,
                        start_column: 10,
                        end_line: 6,
                        end_column: 14
                    }
                },
            ]
        );
    }

    #[test]
    fn test_nested_function_complexity() {
        let source_code = r#"
fn foo(c1: bool) {
    fn nested(c2: bool) {
        if c2 { // +2 (incl 1 for nesting)
        
        }
    }

    if c1 { // +1
    
    }
}
"#;
        let rule = CognitiveComplexityCheck::new(0);
        let tree = parse_rust_code(source_code).unwrap();

        let actual = rule.check(&tree, source_code).unwrap();

        assert_eq!(actual.len(), 1);
        assert_eq!(actual[0].rule_key, RULE_KEY);
        assert_eq!(
            actual[0].message,
            "Refactor this function to reduce its Cognitive Complexity from 3 to the 0 allowed."
        );
        assert_eq!(
            actual[0].location,
            SonarLocation {
                start_line: 2,
                start_column: 3,
                end_line: 2,
                end_column: 6
            }
        );

        assert_eq!(
            actual[0].secondary_locations,
            vec![
                SecondaryLocation {
                    message: "+2 (incl 1 for nesting)".to_string(),
                    location: SonarLocation {
                        start_line: 4,
                        start_column: 8,
                        end_line: 4,
                        end_column: 10
                    }
                },
                SecondaryLocation {
                    message: "+1".to_string(),
                    location: SonarLocation {
                        start_line: 9,
                        start_column: 4,
                        end_line: 9,
                        end_column: 6
                    }
                },
            ]
        );
    }

    #[test]
    fn test_default_threshold() {
        let source_code = r#"
fn foo(c1: bool) {
    if c1 {} else {}
    if c1 {} else {}
    if c1 {} else {}
    if c1 {} else {}
    if c1 {} else {}
    if c1 {} else {}
    if c1 {} else {}
    if c1 {} else {}
}
"#;
        let rule = CognitiveComplexityCheck::new(15);
        let tree = parse_rust_code(source_code).unwrap();

        let actual = rule.check(&tree, source_code).unwrap();
        assert_eq!(actual.len(), 1);

        assert_eq!(actual.len(), 1);
        assert_eq!(actual[0].rule_key, RULE_KEY);
        assert_eq!(
            actual[0].message,
            "Refactor this function to reduce its Cognitive Complexity from 16 to the 15 allowed."
        );
        assert_eq!(
            actual[0].location,
            SonarLocation {
                start_line: 2,
                start_column: 3,
                end_line: 2,
                end_column: 6
            }
        );
        assert_eq!(actual[0].secondary_locations.len(), 16);
    }
}
