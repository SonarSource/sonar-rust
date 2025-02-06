/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.analysis;

import com.sonarsource.rust.plugin.Analyzer;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AnalyzerTest {

  public static final List<String> RUN_LOCAL_ANALYZER_COMMAND = List.of("cargo", "run", "--manifest-path", "../analyzer/Cargo.toml");

  @Test
  void analyze() throws InterruptedException {
    Analyzer analyzer = new Analyzer(RUN_LOCAL_ANALYZER_COMMAND);
    var result = analyzer.analyze("fn main() {}");

    assertThat(result.highlightTokens()).containsExactly(new Analyzer.HighlightTokens("KEYWORD", 1, 0, 1, 2));
  }

  @Test
  void analyze_command_fail() {
    Analyzer analyzer = new Analyzer(List.of("cargo", "run", "--manifest-path", "../analyzer/not-existing"));
    assertThatCode(() -> analyzer.analyze("fn main() {}"))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("exit code");
  }
}
