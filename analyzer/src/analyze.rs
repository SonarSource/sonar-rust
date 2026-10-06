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
use crate::{
    issue::{find_issues_with_recursion, Issue},
    recursion::Recursion,
    tree::{parse_rust_code, AnalyzerError},
    visitors::{
        cpd::{calculate_cpd_tokens, CpdToken},
        highlight::{highlight, HighlightToken},
        metrics::{calculate_metrics_with_recursion, Metrics},
    },
};
use std::collections::{HashMap, HashSet};

#[derive(Debug)]
pub struct Output {
    pub highlight_tokens: Vec<HighlightToken>,
    pub metrics: Metrics,
    pub cpd_tokens: Vec<CpdToken>,
    pub issues: Vec<Issue>,
}

pub fn analyze(
    source_code: &str,
    parameters: &HashMap<String, String>,
) -> Result<Output, AnalyzerError> {
    analyze_project_file(source_code, parameters, None)
}

pub fn analyze_project_file(
    source_code: &str,
    parameters: &HashMap<String, String>,
    ranges: Option<&HashSet<(usize, usize)>>,
) -> Result<Output, AnalyzerError> {
    let tree = parse_rust_code(source_code)?;
    let recursion = match ranges {
        Some(ranges) => Recursion::for_file(tree.root_node(), ranges),
        None => Recursion::new(tree.root_node(), source_code),
    };

    Ok(Output {
        highlight_tokens: highlight(&tree, source_code)?,
        metrics: calculate_metrics_with_recursion(&tree, source_code, &recursion)?,
        cpd_tokens: calculate_cpd_tokens(&tree, source_code)?,
        issues: find_issues_with_recursion(&tree, source_code, parameters, &recursion)?,
    })
}

#[cfg(test)]
mod tests {
    use std::vec;

    use crate::tree::SonarLocation;
    use crate::visitors::highlight::HighlightTokenType;

    use super::*;

    #[test]
    fn recursion_fixture_reports_every_cycle_member_and_excludes_entry() {
        let source = include_str!("../tests/fixtures/recursion.rs");
        let parameters = HashMap::from([("S3776:threshold".to_owned(), "1".to_owned())]);
        let output = analyze(source, &parameters).unwrap();
        assert_eq!(output.metrics.cognitive_complexity, 9);
        assert_eq!(output.issues.len(), 3);
        assert!(output
            .issues
            .iter()
            .all(|issue| issue.message.contains("from 3 to the 1 allowed")
                && issue.secondary_locations.len() == 3));
    }

    #[test]
    fn resolved_method_cycle_updates_metrics_and_s3776_locations() {
        let source = "struct S;\nimpl S {\n    fn récurse(&self) { helper(self); }\n}\nfn helper(s: &S) {\n    s.récurse();\n}\nfn entry(s: &S) { helper(s); }";
        let parameters = HashMap::from([("S3776:threshold".to_owned(), "0".to_owned())]);
        let output = analyze(source, &parameters).unwrap();
        assert_eq!(output.metrics.cognitive_complexity, 2);
        assert_eq!(output.issues.len(), 2);
        assert!(output
            .issues
            .iter()
            .all(|issue| issue.rule_key == "S3776" && issue.secondary_locations.len() == 1));
        let helper_issue = output
            .issues
            .iter()
            .find(|issue| issue.location.start_line == 5)
            .unwrap();
        assert_eq!(helper_issue.secondary_locations[0].message, "+1");
        assert_eq!(
            helper_issue.secondary_locations[0].location,
            SonarLocation {
                start_line: 6,
                start_column: 6,
                end_line: 6,
                end_column: 13,
            }
        );
    }

    #[test]
    fn test_analyze() {
        let source_code = r#"
/// The main function
fn main() {
    // This is a comment
    let x = 42;
    println!("Hello, world!");
}
        "#;
        let output = analyze(source_code, &test_parameters()).unwrap();

        assert_eq!(
            output.metrics,
            Metrics {
                ncloc: 4,
                comment_lines: 2,
                functions: 1,
                statements: 2,
                classes: 0,
                cognitive_complexity: 0,
                cyclomatic_complexity: 1
            }
        );

        let mut actual_highlighting = output.highlight_tokens.clone();
        actual_highlighting.sort();

        let mut expected_highlighting = vec![
            HighlightToken {
                token_type: HighlightTokenType::StructuredComment,
                location: SonarLocation {
                    start_line: 2,
                    start_column: 0,
                    end_line: 3,
                    end_column: 0,
                },
            },
            HighlightToken {
                token_type: HighlightTokenType::Keyword,
                location: SonarLocation {
                    start_line: 3,
                    start_column: 0,
                    end_line: 3,
                    end_column: 2,
                },
            },
            HighlightToken {
                token_type: HighlightTokenType::Comment,
                location: SonarLocation {
                    start_line: 4,
                    start_column: 4,
                    end_line: 4,
                    end_column: 24,
                },
            },
            HighlightToken {
                token_type: HighlightTokenType::Keyword,
                location: SonarLocation {
                    start_line: 5,
                    start_column: 4,
                    end_line: 5,
                    end_column: 7,
                },
            },
            HighlightToken {
                token_type: HighlightTokenType::Constant,
                location: SonarLocation {
                    start_line: 5,
                    start_column: 12,
                    end_line: 5,
                    end_column: 14,
                },
            },
            HighlightToken {
                token_type: HighlightTokenType::String,
                location: SonarLocation {
                    start_line: 6,
                    start_column: 13,
                    end_line: 6,
                    end_column: 28,
                },
            },
        ];
        expected_highlighting.sort();

        assert_eq!(expected_highlighting, actual_highlighting);

        let issues = output.issues;
        assert_eq!(issues.len(), 0);
    }

    #[test]
    fn test_unicode() {
        // 4 byte value
        assert_eq!(
            analyze("//𠱓", &test_parameters())
                .unwrap()
                .highlight_tokens,
            vec![HighlightToken {
                token_type: HighlightTokenType::Comment,
                location: SonarLocation {
                    start_line: 1,
                    start_column: 0,
                    end_line: 1,
                    end_column: 4,
                }
            }]
        );
        assert_eq!("𠱓".as_bytes().len(), 4);

        // 3 byte unicode
        assert_eq!(
            analyze("//ॷ", &test_parameters()).unwrap().highlight_tokens,
            vec![HighlightToken {
                token_type: HighlightTokenType::Comment,
                location: SonarLocation {
                    start_line: 1,
                    start_column: 0,
                    end_line: 1,
                    end_column: 3,
                }
            }]
        );
        assert_eq!("ࢣ".as_bytes().len(), 3);

        // 2 byte unicode
        assert_eq!(
            analyze("//©", &test_parameters()).unwrap().highlight_tokens,
            vec![HighlightToken {
                token_type: HighlightTokenType::Comment,
                location: SonarLocation {
                    start_line: 1,
                    start_column: 0,
                    end_line: 1,
                    end_column: 3,
                }
            }]
        );
        assert_eq!("©".as_bytes().len(), 2);
    }

    #[test]
    fn test_multiple_unicode_locations() {
        let mut actual = analyze("/*𠱓𠱓*/ //𠱓", &test_parameters())
            .unwrap()
            .highlight_tokens;
        actual.sort();

        let mut expected = vec![
            HighlightToken {
                token_type: HighlightTokenType::Comment,
                location: SonarLocation {
                    start_line: 1,
                    start_column: 0,
                    end_line: 1,
                    end_column: 8,
                },
            },
            HighlightToken {
                token_type: HighlightTokenType::Comment,
                location: SonarLocation {
                    start_line: 1,
                    start_column: 9,
                    end_line: 1,
                    end_column: 13,
                },
            },
        ];
        expected.sort();

        assert_eq!(actual, expected);
    }

    #[test]
    fn test_multi_line_unicode() {
        let mut actual = analyze("/*\n𠱓\n𠱓\n    𠱓*/", &test_parameters())
            .unwrap()
            .highlight_tokens;
        actual.sort();

        let mut expected = vec![HighlightToken {
            token_type: HighlightTokenType::Comment,
            location: SonarLocation {
                start_line: 1,
                start_column: 0,
                end_line: 4,
                end_column: 8,
            },
        }];
        expected.sort();

        assert_eq!(actual, expected);
    }

    fn test_parameters() -> HashMap<String, String> {
        HashMap::from([("S3776:threshold".to_string(), "15".to_string())])
    }
}
