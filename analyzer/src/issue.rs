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
use crate::rules::rule::all_rules;
use crate::tree::{AnalyzerError, SonarLocation};
use tree_sitter::Tree;

#[derive(Debug, PartialEq, Eq, PartialOrd, Ord, Clone)]
pub struct Issue {
    pub rule_key: String,
    pub message: String,
    pub location: SonarLocation,
    pub secondary_locations: Vec<SecondaryLocation>
}

#[derive(Debug, PartialEq, Eq, PartialOrd, Ord, Clone)]
pub struct SecondaryLocation {
    pub message: String,
    pub location: SonarLocation
}

pub fn find_issues(tree: &Tree, source_code: &str) -> Result<Vec<Issue>, AnalyzerError> {
    let mut issues = Vec::new();
    for rule in all_rules() {
        issues.extend(rule.check(tree, source_code)?);
    }
    Ok(issues)
}
