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
use crate::recursion::Recursion;
use crate::tree::{walk_tree, AnalyzerError, NodeVisitor, TreeSitterLocation};
use std::collections::HashSet;
use tree_sitter::Node;
#[cfg(test)]
use tree_sitter::Tree;

#[allow(dead_code)] // Location is currently only used in tests, so we allow dead code
pub struct Increment {
    pub location: TreeSitterLocation,
    pub nesting: i32,
}

#[cfg(test)]
pub fn calculate_total_cognitive_complexity(
    tree: &Tree,
    source_code: &str,
) -> Result<i32, AnalyzerError> {
    Ok(
        calculate_cognitive_complexity(tree.root_node(), source_code)?
            .iter()
            .map(|inc| inc.nesting + 1)
            .sum(),
    )
}

#[cfg(test)]
pub fn calculate_cognitive_complexity(
    node: Node<'_>,
    source_code: &str,
) -> Result<Vec<Increment>, AnalyzerError> {
    let mut root = node;
    while let Some(parent) = root.parent() {
        root = parent;
    }
    let recursion = Recursion::new(root, source_code);
    calculate_with_recursion(node, &recursion)
}

pub fn calculate_with_recursion(
    node: Node<'_>,
    recursion: &Recursion,
) -> Result<Vec<Increment>, AnalyzerError> {
    let mut visitor = ComplexityVisitor {
        recursion,
        current_increments: Vec::new(),
        visited_operators: HashSet::new(),
        current_nesting: 0,
        current_enclosing_functions: 0,
    };
    walk_tree(node, &mut visitor)?;
    Ok(visitor.current_increments)
}

struct ComplexityVisitor<'a> {
    recursion: &'a Recursion,
    current_increments: Vec<Increment>,
    visited_operators: HashSet<usize>,
    current_nesting: i32,
    current_enclosing_functions: i32,
}

impl ComplexityVisitor<'_> {
    fn increment_with_nesting(&mut self, location: Node<'_>, nesting_level: i32) {
        self.current_increments.push(Increment {
            location: TreeSitterLocation::from_tree_sitter_node(location),
            nesting: nesting_level,
        });
    }

    fn increment_without_nesting(&mut self, location: Node<'_>) {
        self.current_increments.push(Increment {
            location: TreeSitterLocation::from_tree_sitter_node(location),
            nesting: 0,
        });
    }
}

impl NodeVisitor for ComplexityVisitor<'_> {
    fn enter_node(&mut self, node: Node<'_>) -> Result<(), AnalyzerError> {
        if let Some(location) = self.recursion.call_location(node) {
            self.current_increments.push(Increment {
                location: location.clone(),
                nesting: 0,
            });
        }
        match node.kind() {
            "function_item" => {
                if self.current_enclosing_functions > 0 {
                    self.current_nesting += 1;
                } else {
                    self.current_nesting = 0;
                }
                self.current_enclosing_functions += 1;
            }
            "if_expression" => {
                if !is_else_if(node) {
                    self.increment_with_nesting(
                        node.child(0).ok_or(AnalyzerError::FileError(
                            "an if expression must have an 'if' keyword child".to_string(),
                        ))?,
                        self.current_nesting,
                    );
                    self.current_nesting += 1;
                }
                if let Some(alternative) = node.child_by_field_name("alternative") {
                    self.increment_without_nesting(alternative.child(0).ok_or(
                        AnalyzerError::FileError(
                            "an else clause must have an 'else' keyword child".to_string(),
                        ),
                    )?);
                }
            }
            "while_expression" | "loop_expression" | "for_expression" | "match_expression" => {
                self.increment_with_nesting(
                    node.child(0).ok_or(AnalyzerError::FileError(
                        "a while/loop/for/match must have their respective keywords as a child"
                            .to_string(),
                    ))?,
                    self.current_nesting,
                );
                self.current_nesting += 1;
            }
            "label" => {
                // break and continue only increase complexity if label is used
                if let Some(parent) = node.parent() {
                    if matches!(parent.kind(), "break_expression" | "continue_expression") {
                        self.increment_without_nesting(parent);
                    }
                }
            }
            "binary_expression" if is_logical_operator(node) => {
                let operator_token =
                    node.child_by_field_name("operator")
                        .ok_or(AnalyzerError::FileError(
                            "operator must be present in binary expression".to_string(),
                        ))?;

                if self.visited_operators.contains(&operator_token.id()) {
                    return Ok(());
                }

                let mut operators = flatten_operators(node)?;
                let mut prev: Option<&str> = None;

                while let Some(operator) = operators.pop() {
                    if prev.is_none() || prev != Some(operator.kind()) {
                        self.increment_without_nesting(operator);
                    }
                    prev = Some(operator.kind());
                    self.visited_operators.insert(operator.id());
                }
            }
            "closure_expression" => {
                self.current_nesting += 1;
            }
            _ => {}
        }

        Ok(())
    }

    fn exit_node(&mut self, node: Node<'_>) -> Result<(), AnalyzerError> {
        match node.kind() {
            "if_expression" => {
                if !is_else_if(node) {
                    self.current_nesting -= 1;
                }
            }
            "while_expression" | "loop_expression" | "for_expression" | "match_expression" => {
                self.current_nesting -= 1;
            }
            "function_item" => {
                self.current_enclosing_functions -= 1;
                if self.current_enclosing_functions > 0 {
                    self.current_nesting -= 1;
                }
            }
            "closure_expression" => {
                self.current_nesting -= 1;
            }
            _ => {}
        }

        Ok(())
    }
}

fn is_else_if(node: Node<'_>) -> bool {
    if let Some(parent) = node.parent() {
        if parent.kind() == "else_clause" && parent.named_child(0) == Some(node) {
            return true;
        }
    }
    false
}

pub(crate) fn is_logical_operator(node: Node<'_>) -> bool {
    if node.kind() != "binary_expression" {
        return false;
    }

    if let Some(operator) = node.child_by_field_name("operator") {
        matches!(operator.kind(), "&&" | "||")
    } else {
        false
    }
}

fn flatten_operators(node: Node<'_>) -> Result<Vec<Node<'_>>, AnalyzerError> {
    let mut operators: Vec<Node<'_>> = vec![];

    if let Some(left) = node.child_by_field_name("left") {
        if is_logical_operator(left) {
            operators.extend(flatten_operators(left)?);
        }
    }

    operators.push(
        node.child_by_field_name("operator")
            .ok_or(AnalyzerError::FileError(
                "operator must be present in a binary expression".to_string(),
            ))?,
    );

    if let Some(right) = node.child_by_field_name("right") {
        if is_logical_operator(right) {
            operators.extend(flatten_operators(right)?);
        }
    }

    Ok(operators)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tree::{parse_rust_code, NodeIterator};
    use tree_sitter::{Query, QueryCursor, StreamingIterator};

    #[derive(Debug, PartialEq)]
    struct IncrementLines {
        line: usize,
        increment: i32,
    }

    #[test]
    fn test_empty() {
        // Empty function with zero complexiy
        check_complexity("");
    }

    #[test]
    fn test_if_else() {
        check_complexity(
            "
if x { // +1
    42
}",
        );
        check_complexity(
            "
    if x { // +1 
        42
    } else { // +1
        43
    }",
        );
        check_complexity(
            "
if x { // +1
    42
} else if y { //+1
    43
}",
        );
        check_complexity(
            "
if x { // +1
    42
} else if y { // +1
    43
} else { // +1
    44
}",
        );
    }

    #[test]
    fn test_nested_else() {
        check_complexity(
            r#"
if x { // +1
    42
} else { // +1
    if y { // +2
        43
    }
}"#,
        );
        check_complexity(
            r#"
if x { // +1
    42
} else if y { // +1
    if z { // +2
        43
    } else { // +1
        44
    }
}"#,
        );
    }

    #[test]
    fn test_while() {
        check_complexity(
            r#"
while cond1 { // +1
    if cond2 { // +2
        42
    } else { // +1
        43
    }
}"#,
        );
        check_complexity(
            r#"
if x { // +1
    while y { // +2
        if z { // +3
        
        }
    }
    if z { // +2
    }
}"#,
        );
    }

    #[test]
    fn test_loop() {
        check_complexity(
            r#"
loop { // +1
    if cond { // +2
        break;
    }
}"#,
        );
    }

    #[test]
    fn test_for() {
        check_complexity(
            r#"
for x in y { // +1
    if cond { // +2
    }
}"#,
        );
    }

    #[test]
    fn test_match() {
        check_complexity(
            r#"
match x { // +1
    "a" => 1,
    "b" => 2,
    _ => 3
}        
"#,
        );
        check_complexity(
            r#"
match x { // +1
    "a" => 1,
    "b" => {
        if y { // +2
        
        } else { //+1
         
        }
    },
    _ => 3
}
"#,
        );
    }

    #[test]
    fn test_break() {
        check_complexity(
            r#"
'outer: for i in 1..=5 { // +1
    '_inner: for j in 1..=200 { // +2
        if j >= 3 { // +3
            break;
        }
        if i >= 2 { // +3
            break 'outer; // +1
        }
    }
}"#,
        );
    }

    #[test]
    fn test_continue() {
        check_complexity(
            r#"
'tens: for ten in 0..3 { // +1
    '_units: for unit in 0..=9 { // +2
        if unit % 2 == 0 { // +3
            continue;
        }
        if unit > 5 { // +3
            continue 'tens; // +1
        }
        println!("{}", ten * 10 + unit);
    }
}
"#,
        );
    }

    #[test]
    fn test_binary_operators() {
        assert_eq!(total_complexity("a && b"), 1);
        assert_eq!(total_complexity("a || b"), 1);
        assert_eq!(total_complexity("a && b && c"), 1);
        assert_eq!(total_complexity("a || b || c"), 1);
        assert_eq!(total_complexity("a || b && c"), 2);
        assert_eq!(total_complexity("a || b && c || d"), 3);
    }

    #[test]
    fn test_nested_binary_operator() {
        assert_eq!(total_complexity("if x { a && b }"), 2);
        assert_eq!(total_complexity("if x { if y && z { 42 } }"), 4);
        assert_eq!(
            total_complexity("for x in 0..5 { if y && z || a { 42 } }"),
            5
        );
    }

    #[test]
    fn test_nested_function() {
        check_complexity(
            r#"
    if x { // +1
    }
    fn nested() {
        if y { // +2
        }
    }
    if z { // +1
    }
"#,
        );
    }

    #[test]
    fn test_closures() {
        check_complexity(
            r#"
    if x { // +1
    }
    invoke(|a, b| {
        if a { // +2
        }    
    });
    if y { // +1
    }
"#,
        );
    }

    #[test]
    fn complex_nested_functions() {
        check_complexity(
            r#"
    let y = foo(x);
    if y == 0 { // +1
        fn foo(x: i32) -> i32 { // this increases nesting level
            if x > 0 { // +3
                42
            } else { // +1
                43
            }
        }

        if z == 0 { // +2
            return 42;
        }
    } else { // +1
        return 44;
    }
    return 45;
        "#,
        );
    }

    #[test]
    fn direct_recursion_is_counted_once_without_nesting() {
        check_complexity(
            r#"
    if n > 0 { // +1
        main(n - 1); // +1
        main(n - 2);
    }
"#,
        );
        check_complexity("main::<i32>(); // +1");
        check_complexity("r#main(); // +1");
    }

    #[test]
    fn nested_functions_have_independent_recursion() {
        check_complexity(
            r#"
    fn nested() {
        nested(); // +1
        main();
    }
    main(); // +1
"#,
        );
        check_complexity("fn main() {} main();");
        check_complexity("main(); fn main() {}");
    }

    #[test]
    fn shadowing_bindings_are_not_recursion() {
        for source in [
            "let main = || {}; main();",
            "let (main, _) = callbacks; main();",
            "let Callbacks { main } = callbacks; main();",
            "let Callbacks { callback: main } = callbacks; main();",
            "let bindings!() = callbacks; main();",
            "for main in callbacks { main(); }",
            "match callbacks { Some(main) => main(), _ => () }",
            "if let Some(main) = callback { main(); }",
            "while let Some(main) = callback { main(); }",
            "invoke(|main| main());",
            "invoke(|main: fn()| main());",
            "struct main; main();",
            "struct main(u8); main(0);",
            "const main: fn() = other; main();",
            "static main: fn() = other; main();",
            "use other::main; main();",
            "use other::*; main();",
            "introduce_bindings!(); main();",
            "unsafe extern \"C\" { fn main(); } main();",
        ] {
            let wrapped = format!("fn main() {{ {source} }}");
            let tree = parse_rust_code(&wrapped).unwrap();
            let increments = calculate_cognitive_complexity(tree.root_node(), &wrapped).unwrap();
            assert!(
                increments.iter().all(|increment| {
                    &wrapped[increment.location.start_byte..increment.location.end_byte] != "main"
                }),
                "Unexpected recursion for {source}"
            );
        }
        let source = "fn main(main: fn()) { main(); }";
        let tree = parse_rust_code(source).unwrap();
        assert_eq!(
            calculate_total_cognitive_complexity(&tree, source).unwrap(),
            0
        );
    }

    #[test]
    fn shadowing_respects_let_initializers_and_block_scope() {
        check_complexity(
            "let main = main(); // +1
main();",
        );
        check_complexity(
            "main(); // +1
let main = other; main();",
        );
        check_complexity("{ let main = other; main(); } main(); // +1");
        check_complexity("invoke(|| main()); // +1");
    }

    #[test]
    fn unresolved_calls_are_not_guessed() {
        check_complexity("other::main(); self.main();");
        check_complexity("(main)(); // +1");
        for source in [
            "impl T { fn main() { main(); Self::main(); } }",
            "impl T { fn main(&self) { self.main(); } }",
            "trait T { fn main() { main(); Self::main(); } }",
            "fn main() { other(); } fn other() {}",
            "fn main() { mod nested { const X: () = main(); } }",
            "fn main() { const X: () = main(); }",
        ] {
            let tree = parse_rust_code(source).unwrap();
            assert_eq!(
                calculate_total_cognitive_complexity(&tree, source).unwrap(),
                0,
                "{source}"
            );
        }
    }

    #[test]
    fn file_metric_and_function_calculation_agree() {
        let source = "mod m { fn r#recurse() { recurse(); } } fn other() { other(); other(); }";
        let tree = parse_rust_code(source).unwrap();
        assert_eq!(
            calculate_total_cognitive_complexity(&tree, source).unwrap(),
            2
        );
        for function in NodeIterator::new(tree.root_node(), |node| node.kind() == "function_item") {
            let increments = calculate_cognitive_complexity(function, source).unwrap();
            assert_eq!(increments.len(), 1);
            assert_eq!(increments[0].nesting, 0);
        }
    }

    fn total_complexity(source_code: &str) -> i32 {
        let wrapped_source = format!("fn main() {{ {}\n }}", source_code);
        let tree = parse_rust_code(&wrapped_source).unwrap();
        calculate_total_cognitive_complexity(&tree, &wrapped_source).unwrap()
    }

    fn check_complexity(source_code: &str) {
        let wrapped_source = format!("fn main() {{ {}\n }}", source_code);
        let tree = parse_rust_code(&wrapped_source).unwrap();

        let increments = calculate_cognitive_complexity(tree.root_node(), &wrapped_source).unwrap();
        let mut expected_increments_by_line = collect_complexity_increments(source_code);

        let actual_total: i32 = increments.iter().map(|inc| inc.nesting + 1).sum();
        let expected_total: i32 = expected_increments_by_line
            .iter()
            .map(|inc| inc.increment)
            .sum();

        assert_eq!(
            actual_total, expected_total,
            "Expected total cognitive complexity to be {}",
            expected_total
        );

        let mut actual_increments_by_line = increments
            .iter()
            .map(|inc| IncrementLines {
                line: inc.location.start_position.row,
                increment: inc.nesting + 1,
            })
            .collect::<Vec<_>>();

        let sort_by_line = |a: &IncrementLines, b: &IncrementLines| a.line.cmp(&b.line);

        actual_increments_by_line.sort_by(sort_by_line);
        expected_increments_by_line.sort_by(sort_by_line);

        assert_eq!(actual_increments_by_line, expected_increments_by_line);
    }

    fn collect_complexity_increments(source_code: &str) -> Vec<IncrementLines> {
        let tree = parse_rust_code(source_code).unwrap();
        let query = Query::new(
            &tree_sitter_rust::LANGUAGE.into(),
            "(line_comment) @comment",
        )
        .expect("parse query");
        let mut cursor = QueryCursor::new();
        let mut matches = cursor.matches(&query, tree.root_node(), source_code.as_bytes());

        let mut increments = vec![];
        while let Some(m) = matches.next() {
            for capture in m.captures() {
                let text = source_code[capture.node.start_byte()..capture.node.end_byte()]
                    .trim_start_matches("//")
                    .trim();
                if text.starts_with("+") {
                    text[1..]
                        .parse::<i32>()
                        .map(|increment| {
                            increments.push(IncrementLines {
                                line: capture.node.start_position().row,
                                increment,
                            })
                        })
                        .expect("parse increment");
                }
            }
        }

        increments
    }
}
