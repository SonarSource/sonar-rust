/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
use crate::{
    cognitive_complexity::calculate_total_cognitive_complexity,
    tree::{parse_rust_code, walk_tree, NodeVisitor, SonarLocation, TreeSitterLocation},
};
use std::collections::HashSet;
use tree_sitter::{Node, Query, QueryCursor, StreamingIterator, Tree};

#[derive(Debug)]
pub struct Output {
    pub highlight_tokens: Vec<HighlightToken>,
    pub metrics: Metrics,
}

// Highlighting token types, as defined by sonar-plugin-api:
//  https://github.com/SonarSource/sonar-plugin-api/blob/master/plugin-api/src/main/java/org/sonar/api/batch/sensor/highlighting/TypeOfText.java
#[derive(Debug, PartialOrd, Ord, PartialEq, Eq, Clone)]
#[allow(dead_code)]
pub enum HighlightTokenType {
    Annotation,
    Constant,
    Comment,
    StructuredComment,
    Keyword,
    String,
    KeywordLight,
    PreprocessDirective,
}

#[derive(Debug, PartialEq, Eq, PartialOrd, Ord, Clone)]
pub struct HighlightToken {
    pub token_type: HighlightTokenType,
    pub location: SonarLocation,
}

#[derive(Debug, PartialEq, Eq, PartialOrd, Ord, Clone, Default)]
pub struct Metrics {
    pub ncloc: i32,
    pub comment_lines: i32,
    pub functions: i32,
    pub statements: i32,
    pub classes: i32,
    pub cognitive_complexity: i32,
}

pub fn process_code(source_code: &str) -> Output {
    let tree = parse_rust_code(source_code);

    Output {
        highlight_tokens: highlight(&tree, source_code),
        metrics: calculate_metrics(&tree, source_code),
    }
}

fn highlight(tree: &Tree, source_code: &str) -> Vec<HighlightToken> {
    let highlight_query = Query::new(
        &tree_sitter_rust::LANGUAGE.into(),
        tree_sitter_rust::HIGHLIGHTS_QUERY,
    )
    .expect("Query parsing error");

    let mut cursor = QueryCursor::new();
    let mut query_matches =
        cursor.matches(&highlight_query, tree.root_node(), source_code.as_bytes());

    let mut tokens: Vec<HighlightToken> = Vec::new();
    let capture_names = highlight_query.capture_names();

    // Doc comments and comments are both matched by the same 'comment' capture, so we need to handle them seperately
    let mut comments: HashSet<Node<'_>> = HashSet::new();
    let mut doc_comments: HashSet<Node<'_>> = HashSet::new();

    while let Some(m) = query_matches.next() {
        for capture in m.captures {
            match HighlightTokenType::from_capture_name(capture_names[capture.index as usize]) {
                Some(HighlightTokenType::Comment) => {
                    comments.insert(capture.node);
                }
                Some(token_type) => {
                    if token_type == HighlightTokenType::StructuredComment {
                        doc_comments.insert(capture.node);
                    }

                    tokens.push(HighlightToken {
                        token_type,
                        location: TreeSitterLocation::from_tree_sitter_node(capture.node)
                            .to_sonar_location(source_code),
                    });
                }
                None => {}
            }
        }
    }

    let comments_without_doc_comments = &comments - &doc_comments;
    for comment in comments_without_doc_comments {
        tokens.push(HighlightToken {
            token_type: HighlightTokenType::Comment,
            location: TreeSitterLocation::from_tree_sitter_node(comment)
                .to_sonar_location(source_code),
        });
    }

    tokens
}

#[derive(Debug)]
struct MetricsVisitor<'a> {
    source_code: &'a str,
    comment_lines: HashSet<usize>,
    lines_of_code: HashSet<usize>,
    functions: i32,
    statements: i32,
    classes: i32,
}

impl<'a> MetricsVisitor<'a> {
    fn new(source_code: &'a str) -> Self {
        Self {
            source_code,
            comment_lines: HashSet::new(),
            lines_of_code: HashSet::new(),
            functions: 0,
            statements: 0,
            classes: 0,
        }
    }

    fn update_metrics(&self, metrics: &mut Metrics) {
        metrics.ncloc = self.lines_of_code.len() as i32;
        metrics.comment_lines = self.comment_lines.len() as i32;
        metrics.functions = self.functions;
        metrics.statements = self.statements;
        metrics.classes = self.classes;
    }
}

impl NodeVisitor for MetricsVisitor<'_> {
    fn exit_node(&mut self, node: Node<'_>) {
        // When this branch is reached, all children of the node have already been processed.
        // Process the information in the node and move on to the next sibling (or backtrack to the parent if there aren't any).
        match node.kind() {
            "line_comment" | "block_comment" => {
                let mut current_line = node.start_position().row;
                for line in self.source_code[node.start_byte()..node.end_byte()].lines() {
                    if !is_blank(line) {
                        self.comment_lines.insert(current_line);
                    }
                    current_line += 1;
                }
            }
            "struct_item" | "enum_item" => {
                self.classes += 1;
            }
            "function_item" => {
                self.functions += 1;
            }
            "expression_statement" | "let_declaration" | "empty_statement" => {
                self.statements += 1;
            }
            _ => {}
        }

        if node.child_count() == 0 {
            // Leaf nodes are tokens, so we add each line as a line of code containing a non-comment token.
            let start_line = node.start_position().row;
            let end_line = node.end_position().row;

            for line in start_line..=end_line {
                self.lines_of_code.insert(line);
            }
        }
    }
}

fn calculate_metrics(tree: &Tree, source_code: &str) -> Metrics {
    let mut metrics_visitor = MetricsVisitor::new(source_code);
    walk_tree(tree.root_node(), &mut metrics_visitor);

    let mut metrics = Metrics::default();
    metrics_visitor.update_metrics(&mut metrics);
    metrics.cognitive_complexity = calculate_total_cognitive_complexity(tree);

    metrics
}

fn is_blank(line: &str) -> bool {
    line.chars()
        .all(|c| c.is_whitespace() || c.is_ascii_punctuation())
}

impl HighlightTokenType {
    fn from_capture_name(name: &str) -> Option<Self> {
        match name {
            "keyword" => Some(HighlightTokenType::Keyword),
            "comment" => Some(HighlightTokenType::Comment),
            "string" => Some(HighlightTokenType::String),
            // Built-in constants are boolean, float and integer literals
            "constant.builtin" => Some(HighlightTokenType::Constant),
            "comment.documentation" => Some(HighlightTokenType::StructuredComment),
            _ => None,
        }
    }

    pub fn to_sonar_api_name(&self) -> &str {
        match self {
            HighlightTokenType::Annotation => "ANNOTATION",
            HighlightTokenType::Constant => "CONSTANT",
            HighlightTokenType::Comment => "COMMENT",
            HighlightTokenType::StructuredComment => "COMMENT",
            HighlightTokenType::Keyword => "KEYWORD",
            HighlightTokenType::String => "STRING",
            HighlightTokenType::KeywordLight => "KEYWORD_LIGHT",
            HighlightTokenType::PreprocessDirective => "PREPROCESS_DIRECTIVE",
        }
    }
}

#[cfg(test)]
mod tests {
    use std::vec;

    use super::*;

    #[test]
    fn test_process_code() {
        let source_code = r#"
/// The main function
fn main() {
    // This is a comment
    let x = 42;
    println!("Hello, world!");
}
        "#;
        let output = process_code(source_code);

        assert_eq!(
            output.metrics,
            Metrics {
                ncloc: 4,
                comment_lines: 2,
                functions: 1,
                statements: 2,
                classes: 0,
                cognitive_complexity: 0
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
    }

    #[test]
    fn test_unicode() {
        // 4 byte value
        assert_eq!(
            process_code("//𠱓").highlight_tokens,
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
            process_code("//ॷ").highlight_tokens,
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
            process_code("//©").highlight_tokens,
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
        let mut actual = process_code("/*𠱓𠱓*/ //𠱓").highlight_tokens;
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
        let mut actual = process_code("/*\n𠱓\n𠱓\n    𠱓*/").highlight_tokens;
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

    #[test]
    fn test_comment_metrics() {
        let actual = process_code(
            r#"
/* Hello */
fn main() {
    let x = "foo"; // Set 'x' to 'foo'
}         
"#,
        )
        .metrics;

        assert_eq!(
            actual,
            Metrics {
                ncloc: 3,
                comment_lines: 2,
                functions: 1,
                statements: 1,
                classes: 0,
                cognitive_complexity: 0
            }
        );
    }

    #[test]
    fn test_comment_metrics_doc_comment() {
        let actual = process_code(
            r#"
/// This main function does something wonderful.
/// Even more wonderful than what you can imagine.
fn main() {
    let x = "foo";
}
"#,
        )
        .metrics;

        assert_eq!(
            actual,
            Metrics {
                ncloc: 3,
                comment_lines: 2,
                functions: 1,
                statements: 1,
                classes: 0,
                cognitive_complexity: 0
            }
        );
    }

    #[test]
    fn test_comment_metrics_empty_lines() {
        let actual = process_code(
            r#"
/**
 * 
 * This is a comment
 **********************
 *
 * blabla
 */
fn main() {
    let x = "foo";
}
"#,
        )
        .metrics;

        assert_eq!(
            actual,
            Metrics {
                ncloc: 3,
                comment_lines: 2,
                functions: 1,
                statements: 1,
                classes: 0,
                cognitive_complexity: 0,
            }
        );
    }

    #[test]
    fn test_class_and_function_metrics() {
        let actual = process_code(
            r#"
enum Color {
    Red,
    Green,
    Blue
}

struct Point {
    x: i32,
    y: i32,
}

impl Point {
    fn new(x: i32, y: i32) -> Point {
        Point { x, y }
    }
}

fn main() {
    let x = "foo";
    ; // Empty statement
}
"#,
        )
        .metrics;

        assert_eq!(
            actual,
            Metrics {
                ncloc: 18,
                comment_lines: 1,
                functions: 2,
                statements: 2,
                classes: 2,
                cognitive_complexity: 0,
            }
        );
    }
}
