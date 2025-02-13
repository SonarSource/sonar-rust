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
      assertThat(result1.measures()).isEqualTo(new Analyzer.Measures(1, 0, 1, 0, 0));
      assertThat(result2.measures()).isEqualTo(new Analyzer.Measures(1, 0, 1, 0, 0));
    }
  }

}
