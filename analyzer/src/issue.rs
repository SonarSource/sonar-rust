/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
use crate::rules::rule::all_rules;
use crate::tree::SonarLocation;
use tree_sitter::Tree;

#[derive(Debug, PartialEq, Eq, PartialOrd, Ord, Clone)]
pub struct Issue {
    pub rule_key: String,
    pub message: String,
    pub location: SonarLocation,
}

pub fn find_issues(tree: &Tree, source_code: &str) -> Vec<Issue> {
    let mut issues = Vec::new();
    for rule in all_rules() {
        issues.extend(rule.check(tree, source_code));
    }
    issues
}
