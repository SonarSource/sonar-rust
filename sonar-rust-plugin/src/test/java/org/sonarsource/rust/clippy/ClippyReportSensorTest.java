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
package org.sonarsource.rust.clippy;

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

  @RegisterExtension
  final LogTesterJUnit5 logTester = new LogTesterJUnit5().setLevel(Level.WARN);

  @TempDir
  Path baseDir;

  @Test
  void testDescribe() {
    var sensor = new ClippyReportSensor();
    var descriptor = new DefaultSensorDescriptor();
    sensor.describe(descriptor);

    assertThat(descriptor.name()).isEqualTo("Clippy Report Import");
    assertThat(descriptor.languages()).containsOnly(RustLanguage.KEY);
    assertThat(descriptor.configurationPredicate()).isNotNull();
  }

  @Test
  void testExecuteWithNoReportsFound() {
    var context = SensorContextTester.create(baseDir);
    var sensor = new ClippyReportSensor();
    sensor.execute(context);

    assertThat(logTester.logs()).contains("No Clippy report files found");
  }

  @Test
  void testExecuteWithFailedToParseReport() throws IOException {
    var json = """
      {"message": {
      """;
    var tempFile = Files.createTempFile(baseDir, "clippy_report", ".json");
    Files.writeString(tempFile, json);

    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty(ClippyReportSensor.CLIPPY_REPORT_PATHS, tempFile.toString());

    var sensor = new ClippyReportSensor();
    sensor.execute(context);

    assertThat(logTester.logs()).contains("Failed to parse Clippy report");

    Files.delete(tempFile);
  }

  @Test
  void testSaveIssueWithDiagnosticEmptySpans() throws IOException {
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

    var sensor = new ClippyReportSensor();
    sensor.execute(context);

    assertThat(logTester.logs()).contains("Failed to save Clippy diagnostic. Empty spans");

    Files.delete(tempFile);
  }

  @Test
  void testSaveIssueWithUnknownFile() throws IOException {
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

    var sensor = new ClippyReportSensor();
    sensor.execute(context);

    assertThat(logTester.logs()).contains("Failed to save Clippy diagnostic. Unknown file: src/main.rs");

    Files.delete(tempFile);
  }

  @Test
  void testSaveIssueWithUnknownRule() throws IOException {
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

    var sensor = new ClippyReportSensor();
    sensor.execute(context);

    assertThat(logTester.logs()).contains("Failed to save Clippy diagnostic. Unknown rule: unknown_rule");

    Files.delete(tempFile);
  }

  @Test
  void testSaveIssueWithValidDiagnostic() throws IOException {
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

    var sensor = new ClippyReportSensor();
    sensor.execute(context);

    var issues = context.allExternalIssues();
    assertThat(issues).hasSize(1);

    var issue = issues.iterator().next();
    assertThat(issue.ruleId()).isEqualTo("approx_constant");
    assertThat(issue.primaryLocation().message()).isEqualTo("approximate value of `f{32, 64}::consts::PI` found");
    assertThat(issue.primaryLocation().textRange().start().line()).isEqualTo(2);
    assertThat(issue.primaryLocation().textRange().start().lineOffset()).isEqualTo(12);
    assertThat(issue.primaryLocation().textRange().end().line()).isEqualTo(2);
    assertThat(issue.primaryLocation().textRange().end().lineOffset()).isEqualTo(16);

    Files.delete(tempFile);
  }
}
