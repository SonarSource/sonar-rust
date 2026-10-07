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
use crate::rules::rule::all_rules;
use crate::tree::{AnalyzerError, SonarLocation};
use std::collections::HashMap;
use tree_sitter::Tree;

#[derive(Debug, PartialEq, Eq, PartialOrd, Ord, Clone)]
pub struct Issue {
    pub rule_key: String,
    pub message: String,
    pub location: SonarLocation,
    pub secondary_locations: Vec<SecondaryLocation>,
}

#[derive(Debug, PartialEq, Eq, PartialOrd, Ord, Clone)]
pub struct SecondaryLocation {
    pub message: String,
    pub location: SonarLocation,
}

pub fn find_issues_with_recursion(
    tree: &Tree,
    source_code: &str,
    parameters: &HashMap<String, String>,
    recursion: &Recursion,
) -> Result<Vec<Issue>, AnalyzerError> {
    let mut issues = Vec::new();
    for rule in all_rules(parameters)? {
        issues.extend(rule.check_with_recursion(tree, source_code, recursion)?);
    }
    Ok(issues)
}
