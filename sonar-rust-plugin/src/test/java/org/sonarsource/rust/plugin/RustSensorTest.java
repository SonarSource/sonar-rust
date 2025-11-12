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

import org.sonarsource.rust.TestAnalysisWarnigs;
import org.sonarsource.rust.plugin.PlatformDetection.Platform;
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
import org.sonar.api.batch.rule.internal.ActiveRulesBuilder;
import org.sonar.api.batch.rule.internal.NewActiveRule;
import org.sonar.api.rule.RuleKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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

  @Test
  void test_unsupported_platform() {
    TestAnalysisWarnigs warnings = new TestAnalysisWarnigs();
    PlatformDetection mockUnsupportedPlatform = new PlatformDetection() {
      @Override
      public Platform detect() {
        return Platform.UNSUPPORTED;
      }

      @Override
      public String debug() {
        return "unit test";
      }

    };
    var sensor = new RustSensor(null, new AnalysisWarningsWrapper(warnings), mockUnsupportedPlatform);

    sensor.execute(context);
    assertThat(warnings.warnings).hasSize(1);
    assertThat(warnings.warnings.get(0)).isEqualTo("Unsupported platform for Rust analysis: unit test");
  }

  @Test
  void test_analyzer_failure() {
    TestAnalysisWarnigs warnings = new TestAnalysisWarnigs();
    var sensor = new RustSensor(new AnalyzerFactory(null) {
      @Override
      public Analyzer create(Platform platform) {
        throw new RuntimeException("Cannot run program");
      }
    }, new AnalysisWarningsWrapper(warnings));

    context.settings().setProperty("sonar.internal.analysis.rust.failFast", "true");

    assertThatThrownBy(() -> sensor.execute(context))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("Analysis failed");
    assertThat(warnings.warnings).hasSize(1);
    assertThat(warnings.warnings.get(0)).startsWith("Failed to create Rust analyzer: Cannot run program");
  }

  @Test
  void active_rule_parameters_passed_to_analyzer_factory() {
    // Capture parameters passed to analyzer factory
    AtomicReference<Map<String, String>> capturedParameters = new AtomicReference<>();

    var mockAnalyzerFactory = new AnalyzerFactory(null) {
      @Override
      public void addParameters(Map<String, String> parameters) {
        capturedParameters.set(Map.copyOf(parameters)); // Capture the parameters
      }

      @Override
      public Analyzer create(Platform platform) {
        return new Analyzer(AnalyzerTest.RUN_LOCAL_ANALYZER_COMMAND, AnalyzerTest.TEST_PARAMETERS);
      }
    };

    var sensor = new RustSensor(mockAnalyzerFactory, new AnalysisWarningsWrapper());

    // Setup active rules with custom parameters
    var activeRulesBuilder = new ActiveRulesBuilder();

    // Add S3776 (cognitive complexity) rule with custom threshold
    activeRulesBuilder.addRule(new NewActiveRule.Builder()
        .setRuleKey(RuleKey.of(RustLanguage.KEY, "S3776"))
        .setParam("threshold", "25") // Custom value, not default "15"
        .build());

    context.setActiveRules(activeRulesBuilder.build());
    context.fileSystem().add(inputFile("test.rs", "fn main() {}"));

    // Execute sensor
    sensor.execute(context);

    // Verify parameters were captured and contain both default and active rule parameters
    Map<String, String> parameters = capturedParameters.get();
    assertThat(parameters)
        .isNotNull()
        .containsEntry("S3776:threshold", "25") // Should contain the active rule parameter (overriding default)
        .doesNotContainEntry("S3776:threshold", "15"); // Verify the default parameter was overridden
  }

  @Test
  void default_rule_parameters_passed_to_analyzer_factory() {
    // Capture parameters passed to analyzer factory
    AtomicReference<Map<String, String>> capturedParameters = new AtomicReference<>();

    var mockAnalyzerFactory = new AnalyzerFactory(null) {
      @Override
      public void addParameters(Map<String, String> parameters) {
        capturedParameters.set(Map.copyOf(parameters)); // Capture the parameters
      }

      @Override
      public Analyzer create(Platform platform) {
        return new Analyzer(AnalyzerTest.RUN_LOCAL_ANALYZER_COMMAND, AnalyzerTest.TEST_PARAMETERS);
      }
    };

    var sensor = new RustSensor(mockAnalyzerFactory, new AnalysisWarningsWrapper());

    // No active rules set - should use default parameters only
    context.fileSystem().add(inputFile("test.rs", "fn main() {}"));

    // Execute sensor
    sensor.execute(context);

    // Verify parameters were captured and contain default values
    Map<String, String> parameters = capturedParameters.get();
    assertThat(parameters)
        .isNotNull()
        .containsEntry("S3776:threshold", "15"); // Should contain the default parameter from RustRulesDefinition.parameters()
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
