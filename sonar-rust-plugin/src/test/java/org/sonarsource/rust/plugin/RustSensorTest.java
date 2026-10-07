/*
 * SonarQube Rust Plugin
 * Copyright (C) SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * You can redistribute and/or modify this program under the terms of
 * the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
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
import org.sonarsource.rust.cargo.CargoManifestProvider;
import org.sonarsource.rust.plugin.PlatformDetection.Platform;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.event.Level;
import org.sonar.api.batch.fs.InputFile;
import com.sonarsource.scanner.engine.sensor.test.fixtures.SensorContextTester;
import com.sonarsource.scanner.engine.sensor.test.fixtures.TestInputFileBuilder;
import org.sonar.api.batch.sensor.highlighting.TypeOfText;
import org.sonar.scanner.plugin.api.impl.sensor.DefaultSensorDescriptor;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.testfixtures.log.LogTesterJUnit5;
import org.sonar.scanner.plugin.api.impl.rule.ActiveRulesBuilder;
import org.sonar.scanner.plugin.api.impl.rule.NewActiveRule;
import org.sonar.api.rule.RuleKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

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
  void initializes_project_and_reports_cross_file_recursion() throws IOException {
    Files.createDirectories(baseDir.toPath().resolve("src"));
    Files.writeString(baseDir.toPath().resolve("Cargo.toml"), "[package]\nname = \"sensor_project\"\nversion = \"0.1.0\"\nedition = \"2021\"\n");
    Files.writeString(baseDir.toPath().resolve("Cargo.lock"), "version = 4\n[[package]]\nname = \"sensor_project\"\nversion = \"0.1.0\"\n");
    String root = "mod other; pub fn a() { other::b(); }";
    String other = "pub fn b() { crate::a(); }";
    Files.writeString(baseDir.toPath().resolve("src/lib.rs"), root);
    Files.writeString(baseDir.toPath().resolve("src/other.rs"), "pub fn b() {}");
    context.fileSystem().add(inputFile("src/lib.rs", root));
    context.fileSystem().add(inputFile("src/other.rs", other));
    sensor().execute(context);
    assertThat(context.measure(PROJECT_KEY + ":src/lib.rs", CoreMetrics.COGNITIVE_COMPLEXITY).value()).isEqualTo(1);
    assertThat(context.measure(PROJECT_KEY + ":src/other.rs", CoreMetrics.COGNITIVE_COMPLEXITY).value()).isEqualTo(1);
  }

  @Test
  void project_initialization_failure_closes_live_analyzer_and_preserves_file_analysis() throws IOException {
    verifyProjectFailureRecovery(false);
  }

  @Test
  void stalled_project_initialization_restarts_and_preserves_file_analysis() throws IOException {
    Files.writeString(baseDir.toPath().resolve("Cargo.toml"), "[package]\nname = \"timeout\"\nversion = \"0.1.0\"\n");
    String root = baseDir.toPath().resolve("src/custom.rs").toString();
    context.fileSystem().add(inputFile("src/custom.rs", "fn a() { crate::a(); }"));
    var creations = new AtomicInteger();
    var stalled = spy(AnalyzerTest.stalledProjectAnalyzer(java.nio.file.Path.of(root)));
    doAnswer(invocation -> assertTimeoutPreemptively(Duration.ofSeconds(10), invocation::callRealMethod))
      .when(stalled).initializeProject(anyList(), anyMap());
    var factory = new AnalyzerFactory(null) {
      @Override
      public Analyzer create(Platform platform) {
        return creations.incrementAndGet() == 1 ? stalled : new Analyzer(AnalyzerTest.RUN_LOCAL_ANALYZER_COMMAND, AnalyzerTest.TEST_PARAMETERS);
      }
    };
    new RustSensor(factory, new AnalysisWarningsWrapper()).execute(context);
    assertThat(creations.get()).isEqualTo(2);
    assertThat(context.measure(PROJECT_KEY + ":src/custom.rs", CoreMetrics.COGNITIVE_COMPLEXITY).value()).isEqualTo(1);
    assertThat(context.highlightingTypeAt(PROJECT_KEY + ":src/custom.rs", 1, 0)).contains(TypeOfText.KEYWORD);
  }

  @Test
  void project_initialization_failure_cleans_up_stopped_analyzer_and_preserves_file_analysis() throws IOException {
    verifyProjectFailureRecovery(true);
  }

  private void verifyProjectFailureRecovery(boolean stopped) throws IOException {
    Files.writeString(baseDir.toPath().resolve("Cargo.toml"), "[package]\nname = \"recovery\"\nversion = \"0.1.0\"\n");
    context.fileSystem().add(inputFile("src/util.rs", "fn a() { a(); }"));
    var creations = new AtomicInteger();
    var failed = new AtomicReference<Analyzer>();
    var factory = new AnalyzerFactory(null) {
      @Override
      public Analyzer create(Platform platform) {
        if (creations.incrementAndGet() == 1) {
          Analyzer analyzer = spy(new Analyzer(AnalyzerTest.RUN_LOCAL_ANALYZER_COMMAND, AnalyzerTest.TEST_PARAMETERS) {
            @Override
            public List<String> initializeProject(List<String> manifests, Map<String, String> sources) throws IOException {
              if (stopped) {
                close();
              }
              throw new IOException("project initialization failed");
            }
          });
          failed.set(analyzer);
          return analyzer;
        }
        return new Analyzer(AnalyzerTest.RUN_LOCAL_ANALYZER_COMMAND, AnalyzerTest.TEST_PARAMETERS);
      }
    };
    new RustSensor(factory, new AnalysisWarningsWrapper()).execute(context);
    assertThat(creations.get()).isEqualTo(2);
    verify(failed.get(), times(stopped ? 2 : 1)).close();
    assertThat(context.measure(PROJECT_KEY + ":src/util.rs", CoreMetrics.COGNITIVE_COMPLEXITY).value()).isEqualTo(1);
    assertThat(context.highlightingTypeAt(PROJECT_KEY + ":src/util.rs", 1, 0)).contains(TypeOfText.KEYWORD);
  }

  @Test
  void project_initialization_honors_fail_fast_without_restarting() throws IOException {
    Files.writeString(baseDir.toPath().resolve("Cargo.toml"), "[package]\nname = \"fail_fast\"\nversion = \"0.1.0\"\n");
    context.settings().setProperty("sonar.internal.analysis.rust.failFast", "true");
    IOException originalFailure = new IOException("invalid project response");
    TestAnalysisWarnigs warnings = new TestAnalysisWarnigs();
    var creations = new AtomicInteger();
    var failed = new AtomicReference<Analyzer>();
    var factory = new AnalyzerFactory(null) {
      @Override
      public Analyzer create(Platform platform) {
        creations.incrementAndGet();
        Analyzer analyzer = spy(new Analyzer(AnalyzerTest.RUN_LOCAL_ANALYZER_COMMAND, AnalyzerTest.TEST_PARAMETERS) {
          @Override
          public List<String> initializeProject(List<String> manifests, Map<String, String> sources) throws IOException {
            throw originalFailure;
          }
        });
        failed.set(analyzer);
        return analyzer;
      }
    };
    var sensor = new RustSensor(factory, new AnalysisWarningsWrapper(warnings));
    assertThatThrownBy(() -> sensor.execute(context))
      .isInstanceOf(IllegalStateException.class).hasMessage("Analysis failed").hasCause(originalFailure);
    assertThat(creations.get()).isEqualTo(1);
    verify(failed.get()).close();
    assertThat(logTester.logs(Level.ERROR)).containsExactly("Rust analysis failed: invalid project response");
    assertThat(warnings.warnings).containsExactly("Rust analysis failed: invalid project response");
  }

  @Test
  void recovery_preserves_custom_cargo_root_without_guessing_nested_lib_module() throws IOException {
    Files.createDirectories(baseDir.toPath().resolve("roots"));
    Files.writeString(baseDir.toPath().resolve("Cargo.toml"), "[package]\nname = \"root_recovery\"\nversion = \"0.1.0\"\nedition = \"2021\"\n[lib]\npath = \"roots/entry.rs\"\n");
    Files.writeString(baseDir.toPath().resolve("Cargo.lock"), "version = 4\n[[package]]\nname = \"root_recovery\"\nversion = \"0.1.0\"\n");
    String root = "mod lib; pub fn parse() { crate::parse(); }";
    String module = "pub fn parse() { crate::parse(); }";
    Files.writeString(baseDir.toPath().resolve("roots/entry.rs"), root);
    Files.writeString(baseDir.toPath().resolve("roots/lib.rs"), module);
    context.fileSystem().add(inputFile("roots/entry.rs", root));
    context.fileSystem().add(inputFile("roots/lib.rs", module));
    var creations = new AtomicInteger();
    var factory = new AnalyzerFactory(null) {
      @Override
      public Analyzer create(Platform platform) {
        if (creations.incrementAndGet() == 1) {
          return new Analyzer(AnalyzerTest.RUN_LOCAL_ANALYZER_COMMAND, AnalyzerTest.TEST_PARAMETERS) {
            @Override
            public List<String> initializeProject(List<String> manifests, Map<String, String> sources) throws IOException {
              super.initializeProject(manifests, sources);
              throw new IOException("project phase failed after root discovery");
            }
          };
        }
        return new Analyzer(AnalyzerTest.RUN_LOCAL_ANALYZER_COMMAND, AnalyzerTest.TEST_PARAMETERS);
      }
    };
    new RustSensor(factory, new AnalysisWarningsWrapper()).execute(context);
    assertThat(creations.get()).isEqualTo(2);
    assertThat(context.measure(PROJECT_KEY + ":roots/entry.rs", CoreMetrics.COGNITIVE_COMPLEXITY).value()).isEqualTo(1);
    assertThat(context.measure(PROJECT_KEY + ":roots/lib.rs", CoreMetrics.COGNITIVE_COMPLEXITY).value()).isZero();
  }

  @Test
  void partial_resolution_logs_one_summary_without_ui_warning_banners() throws IOException {
    Files.writeString(baseDir.toPath().resolve("Cargo.toml"), "[package]\nname = \"partial\"\nversion = \"0.1.0\"\n");
    context.fileSystem().add(inputFile("src/util.rs", "fn a() { a(); }"));
    TestAnalysisWarnigs warnings = new TestAnalysisWarnigs();
    var factory = new AnalyzerFactory(null) {
      @Override
      public Analyzer create(Platform platform) {
        return new Analyzer(AnalyzerTest.RUN_LOCAL_ANALYZER_COMMAND, AnalyzerTest.TEST_PARAMETERS) {
          @Override
          public List<String> initializeProject(List<String> manifests, Map<String, String> sources) {
            return List.of("Dependency resolution unavailable", "Module generated source unavailable");
          }
        };
      }
    };
    new RustSensor(factory, new AnalysisWarningsWrapper(warnings)).execute(context);
    assertThat(warnings.warnings).isEmpty();
    assertThat(logTester.logs(Level.WARN).stream().filter(message -> message.startsWith("Rust project resolution")).toList()).hasSize(1).allSatisfy(message -> assertThat(message).contains("cross-file recursion detection may be incomplete"));
    assertThat(logTester.logs(Level.DEBUG)).anySatisfy(message -> assertThat(message).contains("Module generated source unavailable"));
    assertThat(context.measure(PROJECT_KEY + ":src/util.rs", CoreMetrics.COGNITIVE_COMPLEXITY).value()).isEqualTo(1);
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
    assertThat(warnings.warnings.get(0)).startsWith("Rust analysis failed: Cannot run program");
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

  @Test
  void reports_dependency_telemetry() throws IOException {
    var manifest = baseDir.toPath().resolve("Cargo.toml");
    Files.writeString(manifest, """
      [dependencies]
      serde = "1.0"
      """);

    var spyContext = spy(context);
    spyContext.settings().setProperty(CargoManifestProvider.CARGO_MANIFEST_PATHS, manifest.toString());
    spyContext.fileSystem().add(inputFile("test.rs", "fn main() {}"));

    sensor().execute(spyContext);

    verify(spyContext).addTelemetryProperty("rust.dependencies", "serde:1.0");
    verify(spyContext).addTelemetryProperty("rust.dependencies.count", "1");
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
