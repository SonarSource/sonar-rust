/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
use crate::{
    issue::{find_issues, Issue},
    tree::{parse_rust_code, AnalyzerError},
    visitors::{
        cpd::{calculate_cpd_tokens, CpdToken},
        highlight::{highlight, HighlightToken},
        metrics::{calculate_metrics, Metrics},
    },
};

#[derive(Debug)]
pub struct Output {
    pub highlight_tokens: Vec<HighlightToken>,
    pub metrics: Metrics,
    pub cpd_tokens: Vec<CpdToken>,
    pub issues: Vec<Issue>,
}

pub fn analyze(source_code: &str) -> Result<Output, AnalyzerError> {
    let tree = parse_rust_code(source_code)?;

    Ok(Output {
        highlight_tokens: highlight(&tree, source_code),
        metrics: calculate_metrics(&tree, source_code),
        cpd_tokens: calculate_cpd_tokens(&tree, source_code),
        issues: find_issues(&tree, source_code),
    })
}

#[cfg(test)]
mod tests {
    use std::vec;

    use crate::tree::SonarLocation;
    use crate::visitors::highlight::HighlightTokenType;

    use super::*;

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
        let output = analyze(source_code).unwrap();

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
            analyze("//𠱓").unwrap().highlight_tokens,
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
            analyze("//ॷ").unwrap().highlight_tokens,
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
            analyze("//©").unwrap().highlight_tokens,
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
        let mut actual = analyze("/*𠱓𠱓*/ //𠱓").unwrap().highlight_tokens;
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
        let mut actual = analyze("/*\n𠱓\n𠱓\n    𠱓*/").unwrap().highlight_tokens;
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
}
