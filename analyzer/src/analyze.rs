/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
use std::collections::HashSet;
use tree_sitter::{Node, Parser, Query, QueryCursor, StreamingIterator, Tree};

#[derive(Debug)]
pub struct Output {
    pub highlight_tokens: Vec<HighlightToken>,
    pub metrics: Metrics,
}

#[derive(Debug, PartialEq, Eq, PartialOrd, Ord, Clone)]
pub struct Location {
    pub start_line: usize,
    pub start_column: usize,
    pub end_line: usize,
    pub end_column: usize,
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
    pub location: Location,
}

#[derive(Debug, PartialEq, Eq, PartialOrd, Ord, Clone, Default)]
pub struct Metrics {
    pub ncloc: i32,
    pub comment_lines: i32,
    pub functions: i32,
    pub statements: i32,
    pub classes: i32,
}

pub fn process_code(source_code: &str) -> Output {
    let mut parser = Parser::new();
    parser
        .set_language(&tree_sitter_rust::LANGUAGE.into())
        .expect("Error loading Rust grammar");

    let tree = parser.parse(source_code, None).expect("Rust parsing error");

    Output {
        highlight_tokens: highlight(&tree, source_code),
        metrics: calculate_metrics(&tree),
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
                        location: node_location(capture.node, source_code),
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
            location: node_location(comment, source_code),
        });
    }

    tokens
}

fn walk_tree<F>(tree: &Tree, mut callback: F)
where
    F: FnMut(Node<'_>),
{
    let mut cursor = tree.walk();
    let mut has_next = true;
    let mut visited_children = false;

    while has_next {
        let node = cursor.node();

        if node.is_extra() {
            // "Extra" nodes are nodes that are not part of the grammer (e.g. comments), so there is no need to visit their children.
            visited_children = true;
        }

        if !visited_children {
            if !cursor.goto_first_child() {
                visited_children = true;
            }
        } else {
            callback(node);

            if cursor.goto_next_sibling() {
                visited_children = false;
            } else if !cursor.goto_parent() {
                has_next = false;
            }
        }
    }
}

fn calculate_metrics(tree: &Tree) -> Metrics {
    let mut metrics = Metrics::default();

    // Lines containing comments
    let mut comment_lines: HashSet<usize> = HashSet::new();
    // Lines containing only comments
    let mut lines_of_code: HashSet<usize> = HashSet::new();

    walk_tree(tree, |node: Node<'_>| {
        // When this branch is reached, all children of the node have already been processed.
        // Process the information in the node and move on to the next sibling (or backtrack to the parent if there aren't any).
        match node.kind() {
            "line_comment" | "block_comment" => {
                let start_line = node.start_position().row;
                let mut end_line = node.end_position().row;

                // Tree-sitter counts lines for doc comments differently, the end line is the line after the last line of the comment.
                if node.child_by_field_name("doc").is_some() {
                    end_line -= 1;
                }

                for line in start_line..=end_line {
                    comment_lines.insert(line);
                }
            }
            "struct_item" | "enum_item" => {
                metrics.classes += 1;
            }
            "function_item" => {
                metrics.functions += 1;
            }
            "expression_statement" => {
                metrics.statements += 1;
            }
            _ => {}
        }

        if node.child_count() == 0 {
            let start_line = node.start_position().row;
            let end_line = node.end_position().row;

            for line in start_line..=end_line {
                lines_of_code.insert(line);
            }
        }
    });

    metrics.ncloc = lines_of_code.len() as i32;
    metrics.comment_lines = comment_lines.len() as i32;

    metrics
}

fn node_location(node: Node<'_>, source_code: &str) -> Location {
    // Tree-sitter and Sonar plugin API have different definitions for column counts.
    //  - Sonar plugin API defines column counts in terms of UTF-16 code units, so © counts as 1, 𠱓 counts as 2
    //  - Tree-sitter defines column counts as the number of bytes from the start of the line, so © counts as 2, ॷ as 3, 𠱓 as 4
    // We will convert between the two definitions by counting UTF-16 code units in the source code.

    // Find the number of UTF-16 code units in the first and the last line of the text range.
    // For both the first and the last line, we extract the byte range of the relevant part of the line, using the byte column offsets.
    // Then, these offsets are converted to an iterator over UTF-16 code units, and the count of these code units is calculated.
    let first_line_start_byte = node.start_byte() - node.start_position().column;
    let first_line_offset =
        str::encode_utf16(&source_code[first_line_start_byte..node.start_byte()]).count() as i32;

    let last_line_start_byte = node.end_byte() - node.end_position().column;
    let last_line_offset =
        str::encode_utf16(&source_code[last_line_start_byte..node.end_byte()]).count() as i32;

    Location {
        start_line: node.start_position().row + 1,
        start_column: first_line_offset as usize,
        end_line: node.end_position().row + 1,
        end_column: last_line_offset as usize,
    }
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
                statements: 1,
                classes: 0,
            }
        );

        let mut actual_highlighting = output.highlight_tokens.clone();
        actual_highlighting.sort();

        let mut expected_highlighting = vec![
            HighlightToken {
                token_type: HighlightTokenType::StructuredComment,
                location: Location {
                    start_line: 2,
                    start_column: 0,
                    end_line: 3,
                    end_column: 0,
                },
            },
            HighlightToken {
                token_type: HighlightTokenType::Keyword,
                location: Location {
                    start_line: 3,
                    start_column: 0,
                    end_line: 3,
                    end_column: 2,
                },
            },
            HighlightToken {
                token_type: HighlightTokenType::Comment,
                location: Location {
                    start_line: 4,
                    start_column: 4,
                    end_line: 4,
                    end_column: 24,
                },
            },
            HighlightToken {
                token_type: HighlightTokenType::Keyword,
                location: Location {
                    start_line: 5,
                    start_column: 4,
                    end_line: 5,
                    end_column: 7,
                },
            },
            HighlightToken {
                token_type: HighlightTokenType::Constant,
                location: Location {
                    start_line: 5,
                    start_column: 12,
                    end_line: 5,
                    end_column: 14,
                },
            },
            HighlightToken {
                token_type: HighlightTokenType::String,
                location: Location {
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
                location: Location {
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
                location: Location {
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
                location: Location {
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
                location: Location {
                    start_line: 1,
                    start_column: 0,
                    end_line: 1,
                    end_column: 8,
                },
            },
            HighlightToken {
                token_type: HighlightTokenType::Comment,
                location: Location {
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
            location: Location {
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
    fn test_comment_metrics_comments() {
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
                statements: 0,
                classes: 0,
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
}
"#,
        )
        .metrics;

        assert_eq!(
            actual,
            Metrics {
                ncloc: 17,
                comment_lines: 0,
                functions: 2,
                statements: 0,
                classes: 2,
            }
        );
    }
}
