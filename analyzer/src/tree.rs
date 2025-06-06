/*
 * SonarQube Rust Plugin
 * Copyright (C) 2025 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the Sonar Source-Available License Version 1, as published by SonarSource SA.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */
use tree_sitter::{Node, Parser, Point, Tree, TreeCursor};

/// Source location as defined by Tree-sitter.
#[derive(Debug, PartialEq, Eq, PartialOrd, Ord, Clone)]
pub(crate) struct TreeSitterLocation {
    pub(crate) start_byte: usize,
    pub(crate) end_byte: usize,
    pub(crate) start_position: Point,
    pub(crate) end_position: Point,
}

/// Source location as expected by the Sonar Plugin API.
///
/// The differences from TreeSitterLocation are:
/// - Line and column numbers are 1-based.
/// - Column counts are in terms of UTF-16 code units (not bytes). For example, '©' counts as 1, '𠱓' counts as 2.
#[derive(Debug, PartialEq, Eq, PartialOrd, Ord, Clone)]
pub struct SonarLocation {
    pub start_line: usize,
    pub start_column: usize,
    pub end_line: usize,
    pub end_column: usize,
}

pub trait NodeVisitor {
    /// Callback invoked the first time a node is visited.
    fn enter_node(&mut self, _node: Node<'_>) -> Result<(), AnalyzerError> {
        Ok(())
    }

    /// Callback invoked after all children of a node have been visited.
    fn exit_node(&mut self, _node: Node<'_>) -> Result<(), AnalyzerError> {
        Ok(())
    }
}

#[derive(Debug)]
pub enum AnalyzerError {
    /// File-level errors that should only prevent the analysis of a single file.
    FileError(String),
    /// Global errors that prevent the analysis of all files.
    GlobalError(String),
}

impl TreeSitterLocation {
    /// Calculate the location of a node, as expected by the Sonar Plugin API.
    ///
    /// Tree-sitter and Sonar plugin API have different definitions for column counts.
    ///  - Sonar plugin API defines column counts in terms of UTF-16 code units, so © counts as 1, 𠱓 counts as 2
    ///  - Tree-sitter defines column counts as the number of bytes from the start of the line, so © counts as 2, ॷ as 3, 𠱓 as 4
    ///
    /// This function converts between the two definitions by counting UTF-16 code units in the source code.
    pub(crate) fn to_sonar_location(&self, source_code: &str) -> SonarLocation {
        // Find the number of UTF-16 code units in the first and the last line of the text range.
        // For both the first and the last line, we extract the byte range of the relevant part of the line, using the byte column offsets.
        // Then, these offsets are converted to an iterator over UTF-16 code units, and the count of these code units is calculated.
        let first_line_start_byte = self.start_byte - self.start_position.column;
        let first_line_offset =
            str::encode_utf16(&source_code[first_line_start_byte..self.start_byte]).count() as i32;

        let last_line_start_byte = self.end_byte - self.end_position.column;
        let last_line_offset =
            str::encode_utf16(&source_code[last_line_start_byte..self.end_byte]).count() as i32;

        SonarLocation {
            start_line: self.start_position.row + 1,
            start_column: first_line_offset as usize,
            end_line: self.end_position.row + 1,
            end_column: last_line_offset as usize,
        }
    }

    pub(crate) fn from_tree_sitter_node(node: Node<'_>) -> Self {
        TreeSitterLocation {
            start_byte: node.start_byte(),
            end_byte: node.end_byte(),
            start_position: node.start_position(),
            end_position: node.end_position(),
        }
    }
}

/// Performs a depth-first traversal of the tree, calling the callbacks defined in the visitor whenever entering and leaving a node.
/// The visitor visits "extra" nodes (e.g. comments) as well, however, it does not visit their children
/// (i.e. comments are treated as leaves in the tree).
pub(crate) fn walk_tree(
    tree: Node<'_>,
    visitor: &mut dyn NodeVisitor,
) -> Result<(), AnalyzerError> {
    let mut cursor = tree.walk();
    let mut has_next = true;
    let mut visited_children = false;

    while has_next {
        let node = cursor.node();

        if node.is_extra() {
            // "Extra" nodes are nodes that are not part of the grammar (e.g. comments), so there is no need to visit their children.
            visited_children = true;
        }

        if !visited_children {
            visitor.enter_node(node)?;
            if !cursor.goto_first_child() {
                visited_children = true;
            }
        } else {
            // When this branch is reached, all children of the node have already been processed.
            // Process the information in the node and move on to the next sibling (or backtrack to the parent if there aren't any).
            visitor.exit_node(node)?;

            if cursor.goto_next_sibling() {
                visited_children = false;
            } else if !cursor.goto_parent() {
                has_next = false;
            }
        }
    }

    Ok(())
}

pub(crate) fn parse_rust_code(source_code: &str) -> Result<Tree, AnalyzerError> {
    let mut parser = Parser::new();
    parser
        .set_language(&tree_sitter_rust::LANGUAGE.into())
        .map_err(|err| {
            AnalyzerError::GlobalError(format!("failed to initialize parser: {:?}", err))
        })?;

    let tree = parser
        .parse(source_code, None)
        .ok_or(AnalyzerError::FileError(
            "failed to parse the source code".to_string(),
        ))?;

    Ok(tree)
}

/// Iterator for iterating over nodes of a tree filtered by a predicate.
///
/// For example, you can use this iterator to iterate over all function nodes in a tree:
/// ```rust
/// let iter = NodeIterator::new(tree.root_node(), |node| node.kind() == "function_item");
/// while let Some(function) = iter.next() {
///     // Handle function...
/// }
/// ```
///
/// The iterator visits all nodes, except for the children "extra" nodes (e.g. comments).
pub struct NodeIterator<'a> {
    predicate: Box<dyn Fn(Node<'a>) -> bool>,
    cursor: TreeCursor<'a>,
    visited_children: bool,
}

impl<'a> NodeIterator<'a> {
    pub fn new<F>(tree: Node<'a>, predicate: F) -> Self
    where
        F: Fn(Node<'a>) -> bool + 'static,
    {
        Self {
            predicate: Box::new(predicate),
            cursor: tree.walk(),
            visited_children: false,
        }
    }
}

impl<'a> Iterator for NodeIterator<'a> {
    type Item = Node<'a>;

    fn next(&mut self) -> Option<Self::Item> {
        loop {
            let node = self.cursor.node();

            if node.is_extra() {
                self.visited_children = true;
            }

            if !self.visited_children {
                if !self.cursor.goto_first_child() {
                    self.visited_children = true;
                }
            } else {
                if self.cursor.goto_next_sibling() {
                    self.visited_children = false;
                } else if !self.cursor.goto_parent() {
                    return None;
                }

                if (self.predicate)(node) {
                    return Some(node);
                }
            }
        }
    }
}

/// Returns a child of a given kind of a node, if it exists.
pub fn child_of_kind<'a>(node: Node<'a>, kind: &str) -> Option<Node<'a>> {
    for i in 0..node.child_count() {
        match node.child(i) {
            Some(child) if child.kind() == kind => return Some(child),
            _ => {}
        }
    }
    None
}
