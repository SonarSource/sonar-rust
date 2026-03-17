/*
 * SonarQube Rust Plugin
 * Copyright (C) 2025-2026 SonarSource Sàrl
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
package org.sonarsource.rust.clippy;

import org.sonarsource.rust.TestAnalysisWarnigs;
import org.sonarsource.rust.plugin.AnalysisWarningsWrapper;
import org.sonarsource.rust.plugin.RustLanguage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.event.Level;
import org.sonar.api.batch.fs.internal.TestInputFileBuilder;
import org.sonar.api.batch.sensor.internal.DefaultSensorDescriptor;
import org.sonar.api.batch.sensor.internal.SensorContextTester;
import org.sonar.api.testfixtures.log.LogTesterJUnit5;

import static org.assertj.core.api.Assertions.assertThat;

class ClippyReportSensorTest {

  private static final String MISSING_REPORT_WARNING = "No Clippy report files found. Check sonar.rust.clippyReport.reportPaths.";
  private static final String INVALID_REPORT_WARNING = "At least one Clippy report could not be read or parsed. See logs for details.";
  private static final String UNKNOWN_FILE_WARNING = "Some Clippy diagnostics refer to files that are not part of the analyzed project.";
  private static final String GENERIC_IMPORT_WARNING = "Some Clippy diagnostics could not be imported; see logs for details.";

  @RegisterExtension
  final LogTesterJUnit5 logTester = new LogTesterJUnit5().setLevel(Level.WARN);

  @TempDir
  Path baseDir;

  @Test
  void testDescribe() {
    var sensor = new ClippyReportSensor(new AnalysisWarningsWrapper());
    var descriptor = new DefaultSensorDescriptor();
    sensor.describe(descriptor);

    assertThat(descriptor.name()).isEqualTo("Clippy Report Import");
    assertThat(descriptor.languages()).containsOnly(RustLanguage.KEY);
    assertThat(descriptor.configurationPredicate()).isNotNull();
  }

  @Test
  void testExecuteWithNoReportsFound() {
    var warnings = new TestAnalysisWarnigs();
    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty(ClippyReportSensor.CLIPPY_REPORT_PATHS, "missing-report.json");
    var sensor = sensor(warnings);
    sensor.execute(context);

    assertThat(logTester.logs()).contains("No Clippy report files found");
    assertThat(warnings.warnings).containsExactly(MISSING_REPORT_WARNING);
  }

  @Test
  void testExecuteWithFailedToParseReport() throws IOException {
    var json = """
      {"message": {
      """;
    var warnings = new TestAnalysisWarnigs();
    var tempFile = Files.createTempFile(baseDir, "clippy_report", ".json");
    Files.writeString(tempFile, json);

    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty(ClippyReportSensor.CLIPPY_REPORT_PATHS, tempFile.toString());

    var sensor = sensor(warnings);
    sensor.execute(context);

    assertThat(logTester.logs()).contains("Failed to parse Clippy report");
    assertThat(warnings.warnings).containsExactly(INVALID_REPORT_WARNING);

    Files.delete(tempFile);
  }

  @Test
  void testSaveIssueWithDiagnosticEmptySpans() throws IOException {
    var warnings = new TestAnalysisWarnigs();
    var json = """
      {"manifest_path": "/dir/Cargo.toml",
       "message": {
        "code": {
          "code": "clippy::approx_constant"
        },
        "spans": []
      }}
      """.replaceAll(System.lineSeparator(), "");
    var tempFile = Files.createTempFile(baseDir, "clippy_report", ".json");
    Files.writeString(tempFile, json);

    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty(ClippyReportSensor.CLIPPY_REPORT_PATHS, tempFile.toString());

    var sensor = sensor(warnings);
    sensor.execute(context);

    assertThat(logTester.logs()).contains("Failed to save Clippy diagnostic. Clippy diagnostic 'clippy::approx_constant' has no location spans");
    assertThat(warnings.warnings).containsExactly(GENERIC_IMPORT_WARNING);

    Files.delete(tempFile);
  }

  @Test
  void testSaveIssueWithUnknownFile() throws IOException {
    var warnings = new TestAnalysisWarnigs();
    var json = """
      {"manifest_path": "/dir/Cargo.toml",
       "message": {
        "code": {
          "code": "clippy::approx_constant"
        },
        "spans": [
          {
            "file_name": "src/main.rs"
          }
        ]
      }}
      """.replaceAll(System.lineSeparator(), "");
    var tempFile = Files.createTempFile(baseDir, "clippy_report", ".json");
    Files.writeString(tempFile, json);

    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty(ClippyReportSensor.CLIPPY_REPORT_PATHS, tempFile.toString());

    var sensor = sensor(warnings);
    sensor.execute(context);

    assertThat(logTester.logs()).contains("Failed to save Clippy diagnostic. Unknown file: src/main.rs");
    assertThat(warnings.warnings).containsExactly(UNKNOWN_FILE_WARNING);

    Files.delete(tempFile);
  }

  @Test
  void testSaveIssueWithUnknownRule() throws IOException {
    var warnings = new TestAnalysisWarnigs();
    var json = """
      {"manifest_path": "/dir/Cargo.toml",
       "message": {
        "code": {
          "code": "clippy::unknown_rule"
        },
        "spans": [
          {
            "file_name": "src/main.rs"
          }
        ]
      }}
      """.replaceAll(System.lineSeparator(), "");
    var tempFile = Files.createTempFile(baseDir, "clippy_report", ".json");
    Files.writeString(tempFile, json);

    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty(ClippyReportSensor.CLIPPY_REPORT_PATHS, tempFile.toString());
    context.fileSystem().add(new TestInputFileBuilder("moduleKey", "src/main.rs").build());

    var sensor = sensor(warnings);
    sensor.execute(context);

    assertThat(logTester.logs()).contains("Failed to save Clippy diagnostic. Unknown rule: unknown_rule");
    assertThat(warnings.warnings).isEmpty();

    Files.delete(tempFile);
  }

  @Test
  void testSaveIssueWithInvalidManifestPath() throws IOException {
    var warnings = new TestAnalysisWarnigs();
    var json = """
      {"manifest_path": "\\u0000",
       "message": {
        "code": {
          "code": "clippy::approx_constant"
        },
        "spans": [
          {
            "file_name": "src/main.rs"
          }
        ]
      }}
      """.replaceAll(System.lineSeparator(), "");
    var tempFile = Files.createTempFile(baseDir, "clippy_report", ".json");
    Files.writeString(tempFile, json);

    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty(ClippyReportSensor.CLIPPY_REPORT_PATHS, tempFile.toString());

    var sensor = sensor(warnings);
    sensor.execute(context);

    assertThat(logTester.logs()).contains("Failed to save Clippy diagnostic. Invalid manifest path: \u0000");
    assertThat(warnings.warnings).containsExactly(GENERIC_IMPORT_WARNING);

    Files.delete(tempFile);
  }

  @Test
  void testWarningsAreDeduplicatedPerCategory() throws IOException {
    var warnings = new TestAnalysisWarnigs();
    var json = String.join(System.lineSeparator(),
      """
      {"manifest_path": "/dir/Cargo.toml",
       "message": {
        "code": {
          "code": "clippy::approx_constant"
        },
        "spans": [
          {
            "file_name": "src/first.rs"
          }
        ]
      }}
      """.replaceAll(System.lineSeparator(), ""),
      """
      {"manifest_path": "/dir/Cargo.toml",
       "message": {
        "code": {
          "code": "clippy::approx_constant"
        },
        "spans": [
          {
            "file_name": "src/second.rs"
          }
        ]
      }}
      """.replaceAll(System.lineSeparator(), ""));
    var tempFile = Files.createTempFile(baseDir, "clippy_report", ".json");
    Files.writeString(tempFile, json);

    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty(ClippyReportSensor.CLIPPY_REPORT_PATHS, tempFile.toString());

    var sensor = sensor(warnings);
    sensor.execute(context);

    assertThat(warnings.warnings).containsExactly(UNKNOWN_FILE_WARNING);

    Files.delete(tempFile);
  }

  @Test
  void testSaveIssueWithValidDiagnostic() throws IOException {
    var warnings = new TestAnalysisWarnigs();
    var manifestPath = baseDir.resolve("Cargo.toml");
    var json = """
      {"manifest_path": "%s",
       "message": {
        "code": {
          "code": "clippy::approx_constant"
        },
        "message": "approximate value of `f{32, 64}::consts::PI` found",
        "spans": [
          {
            "file_name": "src/main.rs",
            "column_end": 17,
            "column_start": 13,
            "line_end": 2,
            "line_start": 2
          }
        ]
      }}
      """.formatted(manifestPath.toString()).replaceAll(System.lineSeparator(), "");
    var tempFile = Files.createTempFile(baseDir, "clippy_report", ".json");
    Files.writeString(tempFile, json);

    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty(ClippyReportSensor.CLIPPY_REPORT_PATHS, tempFile.toString());

    var sourceCode = """
      fn main() {
          let x = 3.14;
      }
      """;
    context.fileSystem().add(
      new TestInputFileBuilder("moduleKey", "src/main.rs")
      .setLanguage(RustLanguage.KEY)
      .setContents(sourceCode)
      .build());

    var sensor = sensor(warnings);
    sensor.execute(context);

    var issues = context.allExternalIssues();
    assertThat(issues).hasSize(1);
    assertThat(warnings.warnings).isEmpty();

    var issue = issues.iterator().next();
    assertThat(issue.ruleId()).isEqualTo("approx_constant");
    assertThat(issue.primaryLocation().message()).isEqualTo("approximate value of `f{32, 64}::consts::PI` found");
    assertThat(issue.primaryLocation().textRange().start().line()).isEqualTo(2);
    assertThat(issue.primaryLocation().textRange().start().lineOffset()).isEqualTo(12);
    assertThat(issue.primaryLocation().textRange().end().line()).isEqualTo(2);
    assertThat(issue.primaryLocation().textRange().end().lineOffset()).isEqualTo(16);

    Files.delete(tempFile);
  }

  @Test
  void testSaveIssueWithWorkspaceRelativeDiagnostic() throws IOException {
    var warnings = new TestAnalysisWarnigs();
    var manifestPath = baseDir.resolve("workspace/crates/core/Cargo.toml");
    var json = """
      {"manifest_path": "%s",
       "message": {
        "code": {
          "code": "clippy::approx_constant"
        },
        "message": "approximate value of `f{32, 64}::consts::PI` found",
        "spans": [
          {
            "file_name": "crates/core/src/main.rs",
            "column_end": 17,
            "column_start": 13,
            "line_end": 2,
            "line_start": 2
          }
        ]
      }}
      """.formatted(manifestPath.toString()).replaceAll(System.lineSeparator(), "");
    var tempFile = Files.createTempFile(baseDir, "clippy_report", ".json");
    Files.writeString(tempFile, json);

    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty(ClippyReportSensor.CLIPPY_REPORT_PATHS, tempFile.toString());

    var sourceCode = """
      fn main() {
          let x = 3.14;
      }
      """;
    context.fileSystem().add(
      new TestInputFileBuilder("moduleKey", "workspace/crates/core/src/main.rs")
        .setLanguage(RustLanguage.KEY)
        .setContents(sourceCode)
        .build());

    var sensor = sensor(warnings);
    sensor.execute(context);

    var issues = context.allExternalIssues();
    assertThat(issues).hasSize(1);
    assertThat(warnings.warnings).isEmpty();

    var issue = issues.iterator().next();
    assertThat(issue.ruleId()).isEqualTo("approx_constant");
    assertThat(issue.primaryLocation().message()).isEqualTo("approximate value of `f{32, 64}::consts::PI` found");

    Files.delete(tempFile);
  }

  @Test
  void testExecuteContinuesAfterDiagnosticResolutionFailure() throws IOException {
    var warnings = new TestAnalysisWarnigs();
    var manifestPath = baseDir.resolve("Cargo.toml");
    var json = """
      {"manifest_path":"%s","message":{"code":{"code":"clippy::approx_constant"},"message":"invalid path","spans":[]}}
      {"manifest_path":"%s","message":{"code":{"code":"clippy::approx_constant"},"message":"approximate value of `f{32, 64}::consts::PI` found","spans":[{"file_name":"src/main.rs","column_end":17,"column_start":13,"line_end":2,"line_start":2}]}}
      """.formatted(manifestPath, manifestPath);
    var tempFile = Files.createTempFile(baseDir, "clippy_report", ".json");
    Files.writeString(tempFile, json);

    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty(ClippyReportSensor.CLIPPY_REPORT_PATHS, tempFile.toString());

    var sourceCode = """
      fn main() {
          let x = 3.14;
      }
      """;
    context.fileSystem().add(
      new TestInputFileBuilder("moduleKey", "src/main.rs")
        .setLanguage(RustLanguage.KEY)
        .setContents(sourceCode)
        .build());

    var sensor = sensor(warnings);
    sensor.execute(context);

    assertThat(logTester.logs()).contains("Failed to save Clippy diagnostic. Clippy diagnostic 'clippy::approx_constant' has no location spans");
    assertThat(warnings.warnings).containsExactly(GENERIC_IMPORT_WARNING);
    var issues = context.allExternalIssues();
    assertThat(issues).hasSize(1);
    assertThat(issues.iterator().next().primaryLocation().message()).isEqualTo("approximate value of `f{32, 64}::consts::PI` found");

    Files.delete(tempFile);
  }

  private static ClippyReportSensor sensor(TestAnalysisWarnigs warnings) {
    return new ClippyReportSensor(new AnalysisWarningsWrapper(warnings));
  }
}
