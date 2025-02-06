/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.clippy;

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

class ClippyReportProviderTest {

  @RegisterExtension
  final LogTesterJUnit5 logTester = new LogTesterJUnit5().setLevel(Level.DEBUG);

  @TempDir
  Path baseDir;

  @Test
  void testNoProvidedReportPaths() {
    var context = SensorContextTester.create(baseDir);

    var reportPaths = new String[]{};
    var reportFiles = ClippyReportProvider.getReportFiles(context, reportPaths);

    assertThat(reportFiles).isEmpty();
  }

  @Test
  void testAbsoluteFileReportPath() throws IOException {
    var tempFile = Files.createTempFile(baseDir, "clippy_report", ".json");
    var context = SensorContextTester.create(baseDir);

    var reportPaths = new String[]{tempFile.toAbsolutePath().toString()};
    var reportFiles = ClippyReportProvider.getReportFiles(context, reportPaths);

    assertThat(reportFiles).hasSize(1);

    Files.delete(tempFile);
  }

  @Test
  void testRelativeFileReportPath() throws IOException {
    var tempFile = Files.createTempFile(baseDir, "clippy_report", ".json");
    var context = SensorContextTester.create(baseDir);

    var reportPaths = new String[]{tempFile.getFileName().toString()};
    var reportFiles = ClippyReportProvider.getReportFiles(context, reportPaths);

    assertThat(reportFiles).hasSize(1);

    Files.delete(tempFile);
  }

  @Test
  void testPatternReportPathMatches() throws IOException {
    var tempFile1 = Files.createTempFile(baseDir, "clippy_report1", ".json");
    var tempFile2 = Files.createTempFile(baseDir, "clippy_report2", ".json");
    var context = SensorContextTester.create(baseDir);

    var reportPaths = new String[]{"**/*.json"};
    var reportFiles = ClippyReportProvider.getReportFiles(context, reportPaths);

    assertThat(reportFiles).hasSize(2);

    Files.delete(tempFile1);
    Files.delete(tempFile2);
  }

  @Test
  void testPatternReportPathNoMatches() throws IOException {
    var tempFile = Files.createTempFile(baseDir, "clippy_report", ".txt");
    var context = SensorContextTester.create(baseDir);

    var reportPaths = new String[]{"**/*.json"};
    var reportFiles = ClippyReportProvider.getReportFiles(context, reportPaths);

    assertThat(reportFiles).isEmpty();

    Files.delete(tempFile);
  }
}
