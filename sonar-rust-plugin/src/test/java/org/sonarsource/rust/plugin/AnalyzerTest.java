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
package org.sonarsource.rust.plugin;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyzerTest {

  public static final List<String> RUN_LOCAL_ANALYZER_COMMAND = List.of("cargo", "run", "--manifest-path", "../analyzer/Cargo.toml");

  public static final Map<String, String> TEST_PARAMETERS = new HashMap<>();
  static {
    for (var param : RustRulesDefinition.parameters()) {
      TEST_PARAMETERS.put(String.format("%s:%s", param.ruleKey(), param.paramKey()), param.defaultValue());
    }
  }

  @Test
  void analyze() throws IOException {
    try (Analyzer analyzer = new Analyzer(RUN_LOCAL_ANALYZER_COMMAND, TEST_PARAMETERS)) {
      var result1 = analyzer.analyze("fn main() {}");
      var result2 = analyzer.analyze("fn foo() -> i32 { 42 }");

      assertThat(result1.highlightTokens()).containsExactly(new Analyzer.HighlightTokens("KEYWORD", new Analyzer.Location(1, 0, 1, 2)));
      assertThat(result2.highlightTokens()).containsExactly(
        new Analyzer.HighlightTokens("KEYWORD", new Analyzer.Location(1, 0, 1, 2)),
        new Analyzer.HighlightTokens("CONSTANT", new Analyzer.Location(1, 18, 1, 20)));
      assertThat(result1.measures()).isEqualTo(new Analyzer.Measures(1, 0, 1, 0, 0, 0, 0));
      assertThat(result2.measures()).isEqualTo(new Analyzer.Measures(1, 0, 1, 0, 0, 0, 1));
    }
  }

  @Test
  void cognitive_and_cyclomatic_complexity() throws IOException {
    try (Analyzer analyzer = new Analyzer(RUN_LOCAL_ANALYZER_COMMAND, TEST_PARAMETERS)) {
      var result = analyzer.analyze("""
        fn foo(x: bool, y: bool) -> i32 {
          if x { // +1
            if y { // +2
              42
            } else { // +1
              43
            }
          } else { // +1
            44
          }
        }

        fn bar(x: i32) -> i32 {
          match x { // +1
            1 => 10,
            2 => 20,
            3 => 30,
            _ => 40
          }
        }
        """);

      assertThat(result.measures().cognitiveComplexity()).isEqualTo(6);
      assertThat(result.measures().cyclomaticComplexity()).isEqualTo(8);

    }

  }

  @Test
  void cpd_tokens() throws IOException {
    try (Analyzer analyzer = new Analyzer(RUN_LOCAL_ANALYZER_COMMAND, TEST_PARAMETERS)) {
      var result = analyzer.analyze("""
        fn main() {
          println!("Hello, world!");
        }
        """);

      assertThat(result.cpdTokens()).containsExactly(
        new Analyzer.CpdToken("fn", new Analyzer.Location(1, 0, 1, 2)),
        new Analyzer.CpdToken("main", new Analyzer.Location(1, 3, 1, 7)),
        new Analyzer.CpdToken("(", new Analyzer.Location(1, 7, 1, 8)),
        new Analyzer.CpdToken(")", new Analyzer.Location(1, 8, 1, 9)),
        new Analyzer.CpdToken("{", new Analyzer.Location(1, 10, 1, 11)),
        new Analyzer.CpdToken("println", new Analyzer.Location(2, 2, 2, 9)),
        new Analyzer.CpdToken("!", new Analyzer.Location(2, 9, 2, 10)),
        new Analyzer.CpdToken("(", new Analyzer.Location(2, 10, 2, 11)),
        new Analyzer.CpdToken("\"", new Analyzer.Location(2, 11, 2, 12)),
        new Analyzer.CpdToken("STRING", new Analyzer.Location(2, 12, 2, 25)),
        new Analyzer.CpdToken("\"", new Analyzer.Location(2, 25, 2, 26)),
        new Analyzer.CpdToken(")", new Analyzer.Location(2, 26, 2, 27)),
        new Analyzer.CpdToken(";", new Analyzer.Location(2, 27, 2, 28)),
        new Analyzer.CpdToken("}",new Analyzer.Location(3, 0, 3, 1)));
    }
  }

  @Test
  void syntax_errors() throws IOException {
    try (Analyzer analyzer = new Analyzer(RUN_LOCAL_ANALYZER_COMMAND, TEST_PARAMETERS)) {
      var result = analyzer.analyze("""
        fn main() {
          let x = 42
        }
        """);

      assertThat(result.issues()).containsExactly(
        new Analyzer.Issue("S2260", "A syntax error occurred during parsing: missing \";\".", new Analyzer.Location(2, 10, 2, 12), Collections.emptyList()));
    }
  }

  @Test
  void cognitive_complexity_check() throws IOException {
    var parameters = new HashMap<>(TEST_PARAMETERS);
    parameters.put(String.format("%s:%s", "S3776", "threshold"), "3");

    try (Analyzer analyzer = new Analyzer(RUN_LOCAL_ANALYZER_COMMAND, parameters)) {
      var result = analyzer.analyze("""
fn foo(c1: bool, c2: bool) {
  if c1 { // +1
    if c2 { // +2
    } else { // +1
    }
  }
}
""");

      assertThat(result.issues()).containsExactly(
        new Analyzer.Issue("S3776", "Refactor this function to reduce its Cognitive Complexity from 4 to the 3 allowed.", new Analyzer.Location(1, 3, 1, 6), List.of(
          new Analyzer.SecondaryLocation("+1", new Analyzer.Location(2, 2, 2, 4)),
          new Analyzer.SecondaryLocation("+2 (incl 1 for nesting)", new Analyzer.Location(3, 4, 3, 6)),
          new Analyzer.SecondaryLocation("+1", new Analyzer.Location(4, 6, 4, 10))
        )));
    }
  }
}
