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
package org.sonarsource.rust.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.event.Level;
import org.sonar.api.batch.sensor.internal.SensorContextTester;
import org.sonar.api.testfixtures.log.LogTesterJUnit5;

class ReportProviderTest {

  @RegisterExtension
  final LogTesterJUnit5 logTester = new LogTesterJUnit5().setLevel(Level.DEBUG);

  @TempDir
  Path baseDir;

  @Test
  void testNoPropertyProvided() {
    var context = SensorContextTester.create(baseDir);

    var reportProvider = new ReportProvider("Some Report", "sonar.some.reportPaths");
    var reportFiles = reportProvider.getReportFiles(context);

    assertThat(reportFiles).isEmpty();
  }

  @Test
  void testNoProvidedReportPaths() {
    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty("sonar.some.reportPaths", "");

    var reportProvider = new ReportProvider("Some Report", "sonar.some.reportPaths");
    var reportFiles = reportProvider.getReportFiles(context);

    assertThat(reportFiles).isEmpty();
  }

  @Test
  void testAbsoluteFileReportPath() throws IOException {
    var tempFile = Files.createTempFile(baseDir, "clippy_report", ".json");

    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty("sonar.some.reportPaths", tempFile.toAbsolutePath().toString());

    var reportProvider = new ReportProvider("Some Report", "sonar.some.reportPaths");
    var reportFiles = reportProvider.getReportFiles(context);

    assertThat(reportFiles).hasSize(1);

    Files.delete(tempFile);
  }

  @Test
  void testRelativeFileReportPath() throws IOException {
    var tempFile = Files.createTempFile(baseDir, "clippy_report", ".json");

    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty("sonar.some.reportPaths", tempFile.getFileName().toString());

    var reportProvider = new ReportProvider("Some Report", "sonar.some.reportPaths");
    var reportFiles = reportProvider.getReportFiles(context);

    assertThat(reportFiles).hasSize(1);

    Files.delete(tempFile);
  }

  @Test
  void testPatternReportPathMatches() throws IOException {
    var tempFile1 = Files.createTempFile(baseDir, "clippy_report1", ".json");
    var tempFile2 = Files.createTempFile(baseDir, "clippy_report2", ".json");

    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty("sonar.some.reportPaths", "**/*.json");

    var reportProvider = new ReportProvider("Some Report", "sonar.some.reportPaths");
    var reportFiles = reportProvider.getReportFiles(context);

    assertThat(reportFiles).hasSize(2);

    Files.delete(tempFile1);
    Files.delete(tempFile2);
  }

  @Test
  void testPatternReportPathNoMatches() throws IOException {
    var tempFile = Files.createTempFile(baseDir, "clippy_report", ".txt");

    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty("sonar.some.reportPaths", "**/*.json");

    var reportProvider = new ReportProvider("Some Report", "sonar.some.reportPaths");
    var reportFiles = reportProvider.getReportFiles(context);

    assertThat(reportFiles).isEmpty();

    Files.delete(tempFile);
  }
}
