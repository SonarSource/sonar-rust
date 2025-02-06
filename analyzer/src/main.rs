/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
use std::{
    collections::HashSet,
    io::{self},
};

use serde::Serialize;
use tree_sitter::{Node, Parser, Query, QueryCursor, StreamingIterator};

#[derive(Debug, Serialize)]
struct Output {
    highlight_tokens: Vec<HighlightToken>,
}

// Highlighting token types, as defined by sonar-plugin-api:
//  https://github.com/SonarSource/sonar-plugin-api/blob/master/plugin-api/src/main/java/org/sonar/api/batch/sensor/highlighting/TypeOfText.java
#[derive(Debug, PartialOrd, Ord, PartialEq, Eq, Serialize, Clone)]
#[allow(dead_code)]
enum HighlightTokenType {
    Annotation,
    Constant,
    Comment,
    StructuredComment,
    Keyword,
    String,
    KeywordLight,
    PreprocessDirective,
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
}

#[derive(Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Clone)]
struct HighlightToken {
    token_type: HighlightTokenType,
    start_line: usize,
    start_column: usize,
    end_line: usize,
    end_column: usize,
}

fn process_code(source_code: &str) -> Output {
    let language = tree_sitter_rust::LANGUAGE;
    let mut parser = Parser::new();
    parser
        .set_language(&language.into())
        .expect("Error loading Rust grammar");

    let highlight_query = Query::new(&language.into(), tree_sitter_rust::HIGHLIGHTS_QUERY)
        .expect("Query parsing error");

    let tree = parser.parse(source_code, None).expect("Rust parsing error");

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
                        // Tree-sitter line numbers are 0-based, but the Sonar API expects 1-based line numbers and 0-based column numbers
                        start_line: capture.node.start_position().row + 1,
                        start_column: capture.node.start_position().column,
                        end_line: capture.node.end_position().row + 1,
                        end_column: capture.node.end_position().column,
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
            start_line: comment.start_position().row + 1,
            start_column: comment.start_position().column,
            end_line: comment.end_position().row + 1,
            end_column: comment.end_position().column,
        });
    }

    Output {
        highlight_tokens: tokens,
    }
}

fn main() {
    let stdin = io::read_to_string(io::stdin()).expect("Read Rust source");
    let output = process_code(&stdin);

    serde_json::to_writer(io::stdout(), &output).expect("Write to stdout");
}

#[cfg(test)]
mod tests {
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

        let mut actual = output.highlight_tokens.clone();
        actual.sort();

        let mut expected = vec![
            HighlightToken {
                token_type: HighlightTokenType::StructuredComment,
                start_line: 2,
                start_column: 0,
                end_line: 3,
                end_column: 0,
            },
            HighlightToken {
                token_type: HighlightTokenType::Keyword,
                start_line: 3,
                start_column: 0,
                end_line: 3,
                end_column: 2,
            },
            HighlightToken {
                token_type: HighlightTokenType::Comment,
                start_line: 4,
                start_column: 4,
                end_line: 4,
                end_column: 24,
            },
            HighlightToken {
                token_type: HighlightTokenType::Keyword,
                start_line: 5,
                start_column: 4,
                end_line: 5,
                end_column: 7,
            },
            HighlightToken {
                token_type: HighlightTokenType::Constant,
                start_line: 5,
                start_column: 12,
                end_line: 5,
                end_column: 14,
            },
            HighlightToken {
                token_type: HighlightTokenType::String,
                start_line: 6,
                start_column: 13,
                end_line: 6,
                end_column: 28,
            },
        ];
        expected.sort();

        assert_eq!(expected, actual);
    }
}
