/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyzerTest {

  public static final List<String> RUN_LOCAL_ANALYZER_COMMAND = List.of("cargo", "run", "--manifest-path", "../analyzer/Cargo.toml");

  @Test
  void analyze() throws IOException {
    try (Analyzer analyzer = new Analyzer(RUN_LOCAL_ANALYZER_COMMAND)) {
      var result1 = analyzer.analyze("fn main() {}");
      var result2 = analyzer.analyze("fn foo() -> i32 { 42 }");

      assertThat(result1.highlightTokens()).containsExactly(new Analyzer.HighlightTokens("KEYWORD", 1, 0, 1, 2));
      assertThat(result2.highlightTokens()).containsExactly(
        new Analyzer.HighlightTokens("KEYWORD", 1, 0, 1, 2),
        new Analyzer.HighlightTokens("CONSTANT", 1, 18, 1, 20));
      assertThat(result1.measures()).isEqualTo(new Analyzer.Measures(1, 0, 1, 0, 0, 0, 0));
      assertThat(result2.measures()).isEqualTo(new Analyzer.Measures(1, 0, 1, 0, 0, 0, 1));
    }
  }

  @Test
  void cognitive_and_cyclomatic_complexity() throws IOException {
    try (Analyzer analyzer = new Analyzer(RUN_LOCAL_ANALYZER_COMMAND)) {
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
    try (Analyzer analyzer = new Analyzer(RUN_LOCAL_ANALYZER_COMMAND)) {
      var result = analyzer.analyze("""
        fn main() {
          println!("Hello, world!");
        }
        """);

      assertThat(result.cpdTokens()).containsExactly(
        new Analyzer.CpdToken("fn", 1, 0, 1, 2),
        new Analyzer.CpdToken("main", 1, 3, 1, 7),
        new Analyzer.CpdToken("(", 1, 7, 1, 8),
        new Analyzer.CpdToken(")", 1, 8, 1, 9),
        new Analyzer.CpdToken("{", 1, 10, 1, 11),
        new Analyzer.CpdToken("println", 2, 2, 2, 9),
        new Analyzer.CpdToken("!", 2, 9, 2, 10),
        new Analyzer.CpdToken("(", 2, 10, 2, 11),
        new Analyzer.CpdToken("\"", 2, 11, 2, 12),
        new Analyzer.CpdToken("STRING", 2, 12, 2, 25),
        new Analyzer.CpdToken("\"", 2, 25, 2, 26),
        new Analyzer.CpdToken(")", 2, 26, 2, 27),
        new Analyzer.CpdToken(";", 2, 27, 2, 28),
        new Analyzer.CpdToken("}",3, 0, 3, 1));
    }
  }

  @Test
  void syntax_errors() throws IOException {
    try (Analyzer analyzer = new Analyzer(RUN_LOCAL_ANALYZER_COMMAND)) {
      var result = analyzer.analyze("""
        fn main() {
          let x = 42
        }
        """);

      assertThat(result.syntaxErrors()).containsExactly(
        new Analyzer.SyntaxError("A syntax error occurred during parsing: missing \";\".", 2, 12, 2, 13));
    }
  }
}
