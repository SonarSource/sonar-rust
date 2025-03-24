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

use crate::{issue::Issue, rules::{cognitive_complexity_check::CognitiveComplexityCheck, parsing_error_check::ParsingErrorCheck}, tree::AnalyzerError};
use tree_sitter::Tree;

pub trait Rule {
    fn check(&self, tree: &Tree, source_code: &str) -> Result<Vec<Issue>, AnalyzerError>;
}

pub fn all_rules() -> Vec<Box<dyn Rule>> {
    vec![
        Box::new(CognitiveComplexityCheck::new(15)),
        Box::new(ParsingErrorCheck::new()),
        // Add other rules here
    ]
}
