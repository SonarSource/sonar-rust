/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.coverage;

import com.sonarsource.rust.plugin.RustLanguage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.event.Level;
import org.sonar.api.batch.fs.internal.TestInputFileBuilder;
import org.sonar.api.batch.sensor.internal.DefaultSensorDescriptor;
import org.sonar.api.batch.sensor.internal.SensorContextTester;
import org.sonar.api.testfixtures.log.LogTesterJUnit5;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageSensorTest {

  @RegisterExtension
  LogTesterJUnit5 logTester = new LogTesterJUnit5().setLevel(Level.WARN);

  @TempDir
  Path baseDir;

  @Test
  void testDescribe() {
    var sensor = new CoverageSensor();
    var descriptor = new DefaultSensorDescriptor();
    sensor.describe(descriptor);

    assertThat(descriptor.name()).isEqualTo("Rust Coverage");
    assertThat(descriptor.languages()).containsOnly(RustLanguage.KEY);
    assertThat(descriptor.configurationPredicate()).isNotNull();
  }

  @Test
  void testExecuteWithNoReportsFound() {
    var context = SensorContextTester.create(baseDir);
    var sensor = new CoverageSensor();
    sensor.execute(context);

    assertThat(logTester.logs()).contains("No LCOV report files found");
  }

  @Test
  void testExecuteWithFailedToParseReport() throws IOException {
    var tempFile = Files.createTempFile(baseDir, "lcov", ".info");
    Files.setPosixFilePermissions(tempFile, Collections.emptySet());

    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty(CoverageSensor.COVERAGE_REPORT_PATHS, tempFile.toString());

    var sensor = new CoverageSensor();
    sensor.execute(context);

    assertThat(logTester.logs()).contains("Failed to parse LCOV report");

    Files.delete(tempFile);
  }

  @Test
  void testExecuteWithParsingProblems() throws IOException {
    var lcovData = """
        SF:src/main.rs
        DA:5,1
        SF:src/lib.rs
        DA:2,1
        """;
    var lcovFile = Files.createTempFile(baseDir, "lcov", ".info");
    Files.writeString(lcovFile, lcovData);

    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty(CoverageSensor.COVERAGE_REPORT_PATHS, lcovFile.toString());

    var fs = context.fileSystem();
    var inputFile = TestInputFileBuilder.create("module", "src/main.rs")
      .setLines(2)
      .build();
    fs.add(inputFile);

    var sensor = new CoverageSensor();
    sensor.execute(context);

    assertThat(logTester.logs()).contains("Found 2 problems in LCOV report: " + lcovFile + ". More details in verbose mode");
  }

  @Test
  void testExecute() throws IOException {
    var lcovData1 = """
        SF:src/abs.rs
        FNF:2
        FNH:2
        DA:1,5
        DA:2,5
        DA:3,2
        DA:5,3
        DA:7,5
        DA:14,1
        DA:15,1
        DA:16,1
        DA:17,1
        DA:18,1
        DA:19,1
        DA:20,1
        BRDA:2,0,0,0
        BRDA:2,0,1,3
        BRF:2
        BRH:2
        LF:12
        LH:12
        end_of_record
        SF:src/main.rs
        FNF:3
        FNH:2
        DA:4,0
        DA:5,0
        DA:6,0
        DA:7,0
        DA:9,4
        DA:10,4
        DA:11,4
        DA:18,1
        DA:19,1
        DA:20,1
        DA:21,1
        DA:22,1
        DA:23,1
        BRF:0
        BRH:0
        LF:13
        LH:9
        end_of_record
        """;
    var lcovFile1 = Files.createTempFile(baseDir, "lcov", ".info");
    Files.writeString(lcovFile1, lcovData1);

    var lcovData2 = """
        SF:src/sign.rs
        DA:1,5
        DA:2,5
        DA:3,2
        DA:4,3
        DA:5,2
        DA:7,1
        DA:9,5
        DA:16,1
        DA:17,1
        DA:18,1
        DA:19,1
        DA:20,1
        DA:21,1
        DA:22,1
        BRDA:2,0,0,2
        BRDA:2,0,1,3
        BRDA:4,0,0,2
        BRDA:4,0,1,1
        BRF:4
        BRH:4
        LF:14
        LH:14
        end_of_record
        """;
    var lcovFile2 = Files.createTempFile(baseDir, "lcov", ".info");
    Files.writeString(lcovFile2, lcovData2);

    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty(CoverageSensor.COVERAGE_REPORT_PATHS, "**/*.info");

    var fs = context.fileSystem();

    var mainFile = TestInputFileBuilder.create("module", "src/main.rs")
      .setLines(30)
      .build();
    fs.add(mainFile);

    var absFile = TestInputFileBuilder.create("module", "src/abs.rs")
      .setLines(25)
      .build();
    fs.add(absFile);

    var signFile = TestInputFileBuilder.create("module", "src/sign.rs")
      .setLines(25)
      .build();
    fs.add(signFile);

    var sensor = new CoverageSensor();
    sensor.execute(context);

    assertThat(logTester.logs()).noneSatisfy(log -> assertThat(log).contains("problems"));

    assertThat(context.lineHits(absFile.key(), 1)).isEqualTo(5);
    assertThat(context.lineHits(absFile.key(), 2)).isEqualTo(5);
    assertThat(context.lineHits(absFile.key(), 3)).isEqualTo(2);
    assertThat(context.lineHits(absFile.key(), 5)).isEqualTo(3);
    assertThat(context.conditions(absFile.key(), 2)).isEqualTo(2);
    assertThat(context.coveredConditions(absFile.key(), 2)).isEqualTo(1);

    assertThat(context.lineHits(mainFile.key(), 4)).isZero();
    assertThat(context.lineHits(mainFile.key(), 5)).isZero();
    assertThat(context.lineHits(mainFile.key(), 6)).isZero();
    assertThat(context.lineHits(mainFile.key(), 9)).isEqualTo(4);
    assertThat(context.lineHits(mainFile.key(), 10)).isEqualTo(4);

    assertThat(context.lineHits(signFile.key(), 1)).isEqualTo(5);
    assertThat(context.lineHits(signFile.key(), 2)).isEqualTo(5);
    assertThat(context.lineHits(signFile.key(), 3)).isEqualTo(2);
    assertThat(context.lineHits(signFile.key(), 4)).isEqualTo(3);
    assertThat(context.lineHits(signFile.key(), 5)).isEqualTo(2);
    assertThat(context.lineHits(signFile.key(), 7)).isEqualTo(1);
    assertThat(context.conditions(signFile.key(), 2)).isEqualTo(2);
    assertThat(context.conditions(signFile.key(), 4)).isEqualTo(2);
    assertThat(context.coveredConditions(signFile.key(), 2)).isEqualTo(2);
    assertThat(context.coveredConditions(signFile.key(), 4)).isEqualTo(2);
  }
}
