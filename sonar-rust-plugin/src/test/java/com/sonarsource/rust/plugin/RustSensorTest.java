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
package com.sonarsource.rust.plugin;

import com.sonarsource.rust.plugin.PlatformDetection.Platform;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.event.Level;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.internal.TestInputFileBuilder;
import org.sonar.api.batch.sensor.highlighting.TypeOfText;
import org.sonar.api.batch.sensor.internal.DefaultSensorDescriptor;
import org.sonar.api.batch.sensor.internal.SensorContextTester;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.testfixtures.log.LogTesterJUnit5;

import static org.assertj.core.api.Assertions.assertThat;

class RustSensorTest {

  public static final String PROJECT_KEY = "moduleKey";

  @RegisterExtension
  protected LogTesterJUnit5 logTester = new LogTesterJUnit5().setLevel(Level.DEBUG);

  @TempDir
  protected File baseDir;
  protected SensorContextTester context;

  @BeforeEach
  void setup() {
    context = SensorContextTester.create(baseDir);
  }

  @Test
  void sensor_descriptor() {
    DefaultSensorDescriptor descriptor = new DefaultSensorDescriptor();
    sensor().describe(descriptor);

    assertThat(descriptor.name()).isEqualTo("Rust");
    assertThat(descriptor.languages()).containsExactly("rust");
  }

  @Test
  void analyze_file() {
    RustSensor sensor = sensor();
    context.fileSystem().add(inputFile("test.rs", "fn main() {}"));
    sensor.execute(context);
    var fnKeyword = context.highlightingTypeAt("%s:test.rs".formatted(PROJECT_KEY), 1, 0);
    assertThat(fnKeyword)
      .containsExactly(TypeOfText.KEYWORD);
    assertThat(context.measure("%s:test.rs".formatted(PROJECT_KEY), CoreMetrics.FUNCTIONS).value())
      .isEqualTo(1);
  }

  @Test
  void analyze_unicode() {
    RustSensor sensor = sensor();
    context.fileSystem().add(inputFile("test1.rs", "//𠱓"));
    context.fileSystem().add(inputFile("test2.rs", "//ॷ"));
    context.fileSystem().add(inputFile("test3.rs", "//©"));

    sensor.execute(context);
    assertThat(context.highlightingTypeAt("%s:test1.rs".formatted(PROJECT_KEY), 1, 0))
      .hasSize(1);
    assertThat(context.highlightingTypeAt("%s:test2.rs".formatted(PROJECT_KEY), 1, 0))
      .hasSize(1);
    assertThat(context.highlightingTypeAt("%s:test3.rs".formatted(PROJECT_KEY), 1, 0))
      .hasSize(1);

    assertThat(context.measure("%s:test1.rs".formatted(PROJECT_KEY), CoreMetrics.COMMENT_LINES).value())
      .isOne();
  }

  @Test
  void analyze_syntax_errors() {
    var sensor = sensor();
    context.fileSystem().add(inputFile("test.rs", "fn main() { let x = 42 }"));

    sensor.execute(context);

    assertThat(context.allIssues()).hasSize(1);

    var issue = context.allIssues().iterator().next();
    assertThat(issue.ruleKey().rule()).isEqualTo("S2260");
    assertThat(issue.primaryLocation().message()).isEqualTo("A syntax error occurred during parsing: missing \";\".");
    assertThat(issue.primaryLocation().textRange().start().line()).isEqualTo(1);
  }

  @Test
  void analyze_syntax_errors_location_at_end_of_line() {
    var sensor = sensor();
    context.fileSystem().add(inputFile("main.rs", """
fn main() {
  let x = 42
}"""));

    sensor.execute(context);

    assertThat(context.allIssues()).hasSize(1);

    var issue = context.allIssues().iterator().next();
    assertThat(issue.ruleKey().rule()).isEqualTo("S2260");
    assertThat(issue.primaryLocation().message()).isEqualTo("A syntax error occurred during parsing: missing \";\".");
    assertThat(issue.primaryLocation().textRange().start().line()).isEqualTo(2);
  }

  @Test
  void analyze_cognitive_complexity() {
    // Test to ensure that the sensor correctly reports secondary locations
    var sensor = sensor();
    context.fileSystem().add(inputFile("test.rs", """
fn foo(c1: bool) {
  if c1 {} else {}
  if c1 {} else {}
  if c1 {} else {}
  if c1 {} else {}
  if c1 {} else {}
  if c1 {} else {}
  if c1 {} else {}
  if c1 {} else {}
}
"""));

    sensor.execute(context);

    assertThat(context.allIssues()).hasSize(1);

    var issue = context.allIssues().iterator().next();
    assertThat(issue.ruleKey().rule()).isEqualTo("S3776");
    assertThat(issue.primaryLocation().message()).isEqualTo("Refactor this function to reduce its Cognitive Complexity from 16 to the 15 allowed.");
    assertThat(issue.primaryLocation().textRange().start().line()).isEqualTo(1);
    assertThat(issue.flows()).hasSize(16);
  }

  private InputFile inputFile(String relativePath, String content) {
    return new TestInputFileBuilder(PROJECT_KEY, relativePath)
      .setModuleBaseDir(baseDir.toPath())
      .setType(InputFile.Type.MAIN)
      .setLanguage(RustLanguage.KEY)
      .setCharset(StandardCharsets.UTF_8)
      .setContents(content)
      .build();
  }

  private RustSensor sensor() {
    return new RustSensor(new AnalyzerFactory(null) {
      @Override
      public Analyzer create(Platform platform) {
        return new Analyzer(AnalyzerTest.RUN_LOCAL_ANALYZER_COMMAND, AnalyzerTest.TEST_PARAMETERS);
      }
    }, new AnalysisWarningsWrapper());
  }

}
