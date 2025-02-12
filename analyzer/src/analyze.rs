use std::collections::HashSet;

/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
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
    functions: i32,
    ncloc: i32,
    statements: i32,
}

pub fn process_code(source_code: &str) -> Output {
    let mut parser = Parser::new();
    parser
        .set_language(&tree_sitter_rust::LANGUAGE.into())
        .expect("Error loading Rust grammar");


    let tree = parser.parse(source_code, None).expect("Rust parsing error");

    Output {
        highlight_tokens: highlight(&tree, source_code),
        metrics: calculate_metrics(&tree, source_code),
    }
}

fn highlight(tree: &Tree, source_code: &str) -> Vec<HighlightToken> {
    let highlight_query = Query::new(&tree_sitter_rust::LANGUAGE.into(), tree_sitter_rust::HIGHLIGHTS_QUERY)
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

fn calculate_metrics(tree: &Tree, source_code: &str) -> Metrics {
    let measures_query = Query::new(&tree_sitter_rust::LANGUAGE.into(), "
(struct_item
    name: (type_identifier) @name) @definition.class

(enum_item
    name: (type_identifier) @name) @definition.class

(union_item
    name: (type_identifier) @name) @definition.class

(function_item
    name: (identifier) @name) @definition.function

(expression_statement) @definition.statement

").expect("Query parsing error");

    let mut cursor = QueryCursor::new();
    let mut query_matches =
        cursor.matches(&measures_query, tree.root_node(), source_code.as_bytes());

    
    let mut metrics = Metrics {
        functions: 0,
        ncloc: 0,
        statements: 0,
    };

    while let Some(m) = query_matches.next() {
        for capture in m.captures {
            match measures_query.capture_names()[capture.index as usize] {
                "definition.class" => {},
                "definition.function" => metrics.functions += 1,
                "definition.statement" => metrics.statements += 1,
                _ => {}
            }
        }
    }

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

        assert_eq!(output.metrics, Metrics {
            functions: 1,
            ncloc: 4,
            statements: 1,
        });

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
}
