/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sonarsource.rust.common.FileLocator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonar.api.batch.fs.internal.TestInputFileBuilder;
import org.sonar.api.batch.sensor.internal.SensorContextTester;

class LCOVParserTest {

  @TempDir
  Path baseDir;

  @Test
  void testCreateParser() throws IOException {
    var lcovData = "";
    var lcovFile = Files.createTempFile(baseDir, "lcov", ".info");
    Files.writeString(lcovFile, lcovData);

    var context = SensorContextTester.create(baseDir);
    var locator = new FileLocator(context.fileSystem().inputFiles());

    assertThatNoException().isThrownBy(() -> LCOVParser.create(context, lcovFile.toFile(), locator));

    Files.delete(lcovFile);
  }

  @Test
  void testCreateParserNonExistingFile() {
    var lcovFile = baseDir.resolve("non-existing-file.info").toFile();

    var context = SensorContextTester.create(baseDir);
    var locator = new FileLocator(context.fileSystem().inputFiles());

    assertThatThrownBy(() -> LCOVParser.create(context, lcovFile, locator))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("Failed to read LCOV report: " + lcovFile);
  }

  @Test
  void testParse() throws IOException {
    var lcovData = """
      SF:path/to/file1.rs
      FN:1,abs
      FNF:1
      FNDA:5,abs
      DA:1,5
      DA:2,5
      DA:3,2
      DA:5,3
      LF:4
      LH:4
      BRDA:2,0,0,2
      BRDA:2,0,1,3
      end_of_record
      SF:path/to/file2.rs
      FN:1,sign
      FNF:1
      FNDA:5,sign
      DA:1,5
      DA:2,5
      DA:3,2
      DA:4,3
      DA:5,2
      DA:7,1
      LF:6
      LH:6
      BRDA:3,0,0,1
      BRDA:3,0,1,1
      BRDA:5,0,0,1
      BRDA:5,0,1,1
      BRDA:7,0,0,1
      BRDA:7,0,1,1
      end_of_record
      """;
    var lcovFile = Files.createTempFile(baseDir, "lcov", ".info");
    Files.writeString(lcovFile, lcovData);

    var context = SensorContextTester.create(baseDir);
    var fs = context.fileSystem();

    var rustCode1 = """
      pub fn abs(value: i32) -> i32 {
        if value < 0 {
          -value
        } else {
          value
        }
      }
      """;
    var inputFile1 = TestInputFileBuilder.create("module", "path/to/file1.rs")
      .setContents(rustCode1)
      .build();
    fs.add(inputFile1);

    var rustCode2 = """
      pub fn sign(value: i32) -> &'static str {
        if value > 0 {
          "positive"
        } else if value < 0 {
          "negative"
        } else {
          "zero"
        }
      }
      """;
    var inputFile2 = TestInputFileBuilder.create("module", "path/to/file2.rs")
      .setContents(rustCode2)
      .build();
    fs.add(inputFile2);

    var locator = new FileLocator(fs.inputFiles());
    var parser = LCOVParser.create(context, lcovFile.toFile(), locator);
    var result = parser.parse();

    var problems = result.problems();
    assertThat(problems).isEmpty();

    var coverages = result.coverages();
    assertThat(coverages).hasSize(2);

    var coverage1 = coverages.get(0);
    assertThat(coverage1.getInputFile()).isEqualTo(inputFile1);
    assertThat(coverage1.getLineHits()).containsAllEntriesOf(Map.of(1, 5, 2, 5, 3, 2, 5, 3));
    assertThat(coverage1.getBranchHits()).containsAllEntriesOf(Map.of(2, Map.of("00", 2, "01", 3)));

    var coverage2 = coverages.get(1);
    assertThat(coverage2.getInputFile()).isEqualTo(inputFile2);
    assertThat(coverage2.getLineHits()).containsAllEntriesOf(Map.of(1, 5, 2, 5, 3, 2, 4, 3, 5, 2, 7, 1));
    assertThat(coverage2.getBranchHits()).containsAllEntriesOf(Map.of(3, Map.of("00", 1, "01", 1), 5, Map.of("00", 1, "01", 1), 7, Map.of("00", 1, "01", 1)));
  }

  @Test
  void testParseAbsolutePath() throws IOException {
    var rustCode = """
      fn main() {}
      """;
    var inputFile = TestInputFileBuilder.create("module", "file.rs")
      .setModuleBaseDir(baseDir)
      .setContents(rustCode)
      .build();

    var context = SensorContextTester.create(baseDir);
    var fs = context.fileSystem();
    fs.add(inputFile);

    var lcovData = """
      SF:%s
      DA:1,1
      end_of_record
      """.formatted(inputFile.file().getAbsolutePath());
    var lcovFile = Files.createTempFile(baseDir, "lcov", ".info");
    Files.writeString(lcovFile, lcovData);

    var locator = new FileLocator(Collections.emptyList());
    var parser = LCOVParser.create(context, lcovFile.toFile(), locator);
    var result = parser.parse();

    var problems = result.problems();
    assertThat(problems).isEmpty();

    var coverages = result.coverages();
    assertThat(coverages).hasSize(1);

    var coverage = coverages.get(0);
    assertThat(coverage.getInputFile()).isEqualTo(inputFile);
    assertThat(coverage.getLineHits()).containsExactlyEntriesOf(Map.of(1, 1));
    assertThat(coverage.getBranchHits()).isEmpty();

    Files.delete(lcovFile);
  }

  @Test
  void testParseEmptyFile() throws IOException {
    var lcovFile = Files.createTempFile(baseDir, "lcov", ".info");

    var context = SensorContextTester.create(baseDir);
    var locator = new FileLocator(context.fileSystem().inputFiles());
    var parser = LCOVParser.create(context, lcovFile.toFile(), locator);

    var result = parser.parse();
    assertThat(result.problems()).isEmpty();
    assertThat(result.coverages()).isEmpty();

    Files.delete(lcovFile);
  }

  @Test
  void testParseSourceFileNotFound() throws IOException {
    var lcovData = """
      SF:path/to/file1.rs
      DA:1,1
      end_of_record
      """;
    var lcovFile = Files.createTempFile(baseDir, "lcov", ".info");
    Files.writeString(lcovFile, lcovData);

    var context = SensorContextTester.create(baseDir);
    var fs = context.fileSystem();

    var locator = new FileLocator(fs.inputFiles());
    var parser = LCOVParser.create(context, lcovFile.toFile(), locator);

    var problems = parser.parse().problems();
    assertThat(problems).hasSize(1);

    var problem = problems.get(0);
    assertThat(problem).isEqualTo(String.format("%s:%d: Invalid SF. File not found: %s", lcovFile, 1, "path/to/file1.rs"));

    Files.delete(lcovFile);
  }

  @Test
  void testParseSourceFileDuplicate() throws IOException {
    var lcovData = """
      SF:path/to/file1.rs
      DA:1,1
      end_of_record
      SF:path/to/file1.rs
      DA:2,2
      end_of_record
      """;
    var lcovFile = Files.createTempFile(baseDir, "lcov", ".info");
    Files.writeString(lcovFile, lcovData);

    var context = SensorContextTester.create(baseDir);
    var fs = context.fileSystem();

    var inputFile = TestInputFileBuilder.create("module", "path/to/file1.rs")
      .setLines(2)
      .build();
    fs.add(inputFile);

    var locator = new FileLocator(fs.inputFiles());
    var parser = LCOVParser.create(context, lcovFile.toFile(), locator);

    var problems = parser.parse().problems();
    assertThat(problems).hasSize(1);

    var problem = problems.get(0);
    assertThat(problem).isEqualTo(String.format("%s:%d: Invalid SF. Duplicate file: %s", lcovFile, 4, "path/to/file1.rs"));

    Files.delete(lcovFile);
  }

  @Test
  void testParseDataLineSyntaxError() throws IOException {
    var lcovData = """
      SF:path/to/file1.rs
      DA:1,
      end_of_record
      """;
    var lcovFile = Files.createTempFile(baseDir, "lcov", ".info");
    Files.writeString(lcovFile, lcovData);

    var context = SensorContextTester.create(baseDir);
    var fs = context.fileSystem();

    var inputFile = TestInputFileBuilder.create("module", "path/to/file1.rs")
      .setLines(1)
      .build();
    fs.add(inputFile);

    var locator = new FileLocator(fs.inputFiles());
    var parser = LCOVParser.create(context, lcovFile.toFile(), locator);

    var problems = parser.parse().problems();
    assertThat(problems).hasSize(1);

    var problem = problems.get(0);
    assertThat(problem).isEqualTo(String.format("%s:%d: Invalid DA. Syntax error", lcovFile, 2));

    Files.delete(lcovFile);
  }

  @Test
  void testParseDataLineNumberFormatError() throws IOException {
    var lcovData = """
      SF:path/to/file1.rs
      DA:1,invalid
      end_of_record
      """;
    var lcovFile = Files.createTempFile(baseDir, "lcov", ".info");
    Files.writeString(lcovFile, lcovData);

    var context = SensorContextTester.create(baseDir);
    var fs = context.fileSystem();

    var inputFile = TestInputFileBuilder.create("module", "path/to/file1.rs")
      .setLines(1)
      .build();
    fs.add(inputFile);

    var locator = new FileLocator(fs.inputFiles());
    var parser = LCOVParser.create(context, lcovFile.toFile(), locator);

    var problems = parser.parse().problems();
    assertThat(problems).hasSize(1);

    var problem = problems.get(0);
    assertThat(problem).isEqualTo(String.format("%s:%d: Invalid DA. Number format error", lcovFile, 2));

    Files.delete(lcovFile);
  }

  @Test
  void testParseBrandDataSyntaxError() throws IOException {
    var lcovData = """
      SF:path/to/file1.rs
      BRDA:1,
      end_of_record
      """;
    var lcovFile = baseDir.resolve("lcov.info");
    Files.writeString(lcovFile, lcovData);

    var context = SensorContextTester.create(baseDir);
    var fs = context.fileSystem();

    var inputFile = TestInputFileBuilder.create("module", "path/to/file1.rs")
      .setLines(1)
      .build();
    fs.add(inputFile);

    var locator = new FileLocator(fs.inputFiles());
    var parser = LCOVParser.create(context, lcovFile.toFile(), locator);

    var problems = parser.parse().problems();
    assertThat(problems).hasSize(1);

    var problem = problems.get(0);
    assertThat(problem).isEqualTo(String.format("%s:%d: Invalid BRDA. Syntax error", lcovFile, 2));

    Files.delete(lcovFile);
  }

  @Test
  void testParseBranchDataNumberFormatError() throws IOException {
    var lcovData = """
      SF:path/to/file1.rs
      BRDA:invalid,1,1,1,1
      end_of_record
      """;
    var lcovFile = baseDir.resolve("lcov.info");
    Files.writeString(lcovFile, lcovData);

    var context = SensorContextTester.create(baseDir);
    var fs = context.fileSystem();

    var inputFile = TestInputFileBuilder.create("module", "path/to/file1.rs")
      .setLines(1)
      .build();
    fs.add(inputFile);

    var locator = new FileLocator(fs.inputFiles());
    var parser = LCOVParser.create(context, lcovFile.toFile(), locator);

    var problems = parser.parse().problems();
    assertThat(problems).hasSize(1);

    var problem = problems.get(0);
    assertThat(problem).isEqualTo(String.format("%s:%d: Invalid BRDA. Number format error", lcovFile, 2));

    Files.delete(lcovFile);
  }

  @Test
  void testParseMultipleProblems() throws IOException {
    var lcovData = """
      SF:path/to/file1.rs
      DA:1,
      BRDA:1,
      end_of_record
      """;
    var lcovFile = baseDir.resolve("lcov.info");
    Files.writeString(lcovFile, lcovData);

    var context = SensorContextTester.create(baseDir);
    var fs = context.fileSystem();

    var inputFile = TestInputFileBuilder.create("module", "path/to/file1.rs")
      .setLines(1)
      .build();
    fs.add(inputFile);

    var locator = new FileLocator(fs.inputFiles());
    var parser = LCOVParser.create(context, lcovFile.toFile(), locator);

    var problems = parser.parse().problems();
    assertThat(problems).hasSize(2);

    var problem1 = problems.get(0);
    assertThat(problem1).isEqualTo(String.format("%s:%d: Invalid DA. Syntax error", lcovFile, 2));

    var problem2 = problems.get(1);
    assertThat(problem2).isEqualTo(String.format("%s:%d: Invalid BRDA. Syntax error", lcovFile, 3));

    Files.delete(lcovFile);
  }
}
