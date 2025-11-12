/*
 * SonarQube Rust Plugin
 * Copyright (C) 2025 SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */

use std::collections::HashMap;

use crate::{
    issue::Issue,
    rules::{
        cognitive_complexity_check::CognitiveComplexityCheck,
        parsing_error_check::ParsingErrorCheck,
    },
    tree::AnalyzerError,
};
use tree_sitter::Tree;

pub trait Rule {
    fn check(&self, tree: &Tree, source_code: &str) -> Result<Vec<Issue>, AnalyzerError>;
}

pub fn all_rules(
    parameters: &HashMap<String, String>,
) -> Result<Vec<Box<dyn Rule>>, AnalyzerError> {
    let cognitive_complexity_threshold = parameters
        .get("S3776:threshold")
        .ok_or(AnalyzerError::GlobalError(
            "rule parameter for 'S3776:threshold' not found".to_string(),
        ))
        .and_then(|value| {
            value.parse::<i32>().map_err(|err| {
                AnalyzerError::GlobalError(format!(
                    "could not parse 'S3776:threshold' parameter: {}",
                    err
                ))
            })
        })?;

    Ok(vec![
        Box::new(CognitiveComplexityCheck::new(
            cognitive_complexity_threshold,
        )),
        Box::new(ParsingErrorCheck::new()),
        // Add other rules here
    ])
}
