/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */

use crate::{issue::Issue, rules::s2260::S2260};
use tree_sitter::Tree;

pub trait Rule {
    fn key(&self) -> &str;
    fn check(&self, tree: &Tree, source_code: &str) -> Vec<Issue>;
}

pub fn all_rules() -> Vec<Box<dyn Rule>> {
    vec![
        Box::new(S2260::new()),
        // Add other rules here
    ]
}
