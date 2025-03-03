/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */

use crate::{issue::Issue, rules::parsing_error_check::ParsingErrorCheck};
use tree_sitter::Tree;

pub trait Rule {
    fn check(&self, tree: &Tree, source_code: &str) -> Vec<Issue>;
}

pub fn all_rules() -> Vec<Box<dyn Rule>> {
    vec![
        Box::new(ParsingErrorCheck::new()),
        // Add other rules here
    ]
}
