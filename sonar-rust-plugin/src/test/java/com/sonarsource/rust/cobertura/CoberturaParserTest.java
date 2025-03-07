/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.cobertura;

import com.sonarsource.rust.common.FileLocator;
import com.sonarsource.rust.coverage.CoberturaParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.internal.TestInputFileBuilder;
import org.sonar.api.batch.sensor.internal.SensorContextTester;
import org.xml.sax.SAXException;

import static org.assertj.core.api.Assertions.assertThat;

class CoberturaParserTest {

  static final Path TEST_PROJECT_FILES_PATH = Paths.get("src", "test", "resources", "cobertura");

  @TempDir
  Path tempDir;

  private SensorContextTester sct;

  @BeforeEach
  void setUp() throws IOException {
    sct = createSensorContext();
  }

  @Test
  void parse_llvm_cov_with_branch_coverage() throws IOException, SAXException {
    Path reportFile = TEST_PROJECT_FILES_PATH.resolve("cobertura.xml");
    var parser = new CoberturaParser(sct, new FileLocator(sct.fileSystem().inputFiles()), reportFile.toString());
    var result = parser.parse(Files.readString(reportFile));

    assertThat(result.problems()).isEmpty();
    assertThat(result.coverages()).hasSize(4);

    var coverages = result.coverages().stream().sorted(Comparator.comparing(c -> c.getInputFile().filename())).toList();
    var absCoverage = coverages.get(0);
    assertThat(absCoverage.getInputFile().filename()).isEqualTo("abs.rs");
    assertThat(absCoverage.getLineHits()).isEqualTo(Map.of(
      1, 1,
      2, 1,
      3, 1,
      5, 0,
      7, 1
    ));
    assertThat(absCoverage.getBranchHits()).isEqualTo(Map.of(
      2, Map.of("0", 1, "1", 0)
    ));

    var fizzBuzzCoverage = coverages.get(1);
    assertThat(fizzBuzzCoverage.getInputFile().filename()).isEqualTo("fizzbuzz.rs");
    assertThat(fizzBuzzCoverage.getLineHits()).containsAllEntriesOf(Map.of(
      2, 2,
      3, 2,
      4, 1,
      5, 1,
      6, 2,
      7, 1,
      8, 1,
      10, 2,
      11, 0
    ));
    assertThat(fizzBuzzCoverage.getBranchHits()).isEqualTo(Map.of(
      3, Map.of("0", 1, "1", 1),
      6, Map.of("0", 1, "1", 1),
      10, Map.of("0", 1, "1", 1, "2", 1, "3", 0)
    ));

    var mainCoverage = coverages.get(2);
    assertThat(mainCoverage.getInputFile().filename()).isEqualTo("main.rs");
    assertThat(mainCoverage.getLineHits()).containsAllEntriesOf(Map.of(
      5, 0,
      6, 0,
      7, 0,
      17, 1,
      18, 1,
      22, 1
    ));
    assertThat(mainCoverage.getBranchHits()).isEmpty();

    var signCoverage = coverages.get(3);
    assertThat(signCoverage.getInputFile().filename()).isEqualTo("sign.rs");
    assertThat(signCoverage.getLineHits()).isEqualTo(Map.of(
      1, 2,
      2, 2,
      3, 1,
      4, 1,
      5, 0,
      7, 1,
      9, 2
    ));
    assertThat(signCoverage.getBranchHits()).isEqualTo(Map.of(
      2, Map.of("0", 1, "1", 1),
      4, Map.of("0", 1, "1", 0)
    ));
  }

  @Test
  void parse_llvm_cov_line_coverage() throws IOException {
    Path reportFile = TEST_PROJECT_FILES_PATH.resolve("cobertura-llvm-cov-line.xml");
    var parser = new CoberturaParser(sct, new FileLocator(sct.fileSystem().inputFiles()), reportFile.toString());
    var result = parser.parse(Files.readString(reportFile));

    assertThat(result.problems()).isEmpty();
    assertThat(result.coverages()).hasSize(4);

    var coverages = result.coverages().stream().sorted(Comparator.comparing(c -> c.getInputFile().filename())).toList();
    var absCoverage = coverages.get(0);
    assertThat(absCoverage.getInputFile().filename()).isEqualTo("abs.rs");
    assertThat(absCoverage.getLineHits()).containsAllEntriesOf(Map.of(
      1, 1,
      2, 1,
      3, 1
    ));
    assertThat(absCoverage.getBranchHits()).isEmpty();
  }

  @Test
  void parse_tarpaulin() throws IOException {
    Path reportFile = TEST_PROJECT_FILES_PATH.resolve("cobertura-tarpaulin-line.xml");
    var parser = new CoberturaParser(sct, new FileLocator(sct.fileSystem().inputFiles()), reportFile.toString());
    var result = parser.parse(Files.readString(reportFile));

    assertThat(result.problems()).isEmpty();
    assertThat(result.coverages()).hasSize(4);

    var coverages = result.coverages().stream().sorted(Comparator.comparing(c -> c.getInputFile().filename())).toList();
    var absCoverage = coverages.get(0);
    assertThat(absCoverage.getInputFile().filename()).isEqualTo("abs.rs");
    assertThat(absCoverage.getLineHits()).containsAllEntriesOf(Map.of(
      1, 1,
      2, 1,
      3, 1
    ));
    assertThat(absCoverage.getBranchHits()).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("parsing_problems")
  void parsing_problems(String expectedMessage, String xml) {
    var result = parse(xml);

    assertThat(result.problems()).hasSize(1);
    assertThat(result.problems().get(0)).contains(expectedMessage);
  }

  @Test
  void no_lines() {
    var result = parse(xmlWithClass("""
      <class name="abs" filename="src/abs.rs">
      </class>
    """));

    assertThat(result.coverages()).hasSize(1);
    assertThat(result.coverages().get(0).getLineHits()).isEmpty();
    assertThat(result.problems()).isEmpty();
  }

  @Test
  void unknown_input_file() {
    var result = parse(xmlWithClass("""
      <class name="abs" filename="does/not/exist/abs.rs">
        <lines><line number="1" hits="1" /></lines>
      </class>
    """));

    assertThat(result.coverages()).isEmpty();
    assertThat(result.problems()).hasSize(1);
    assertThat(result.problems().get(0)).contains("Input file not found for path: does/not/exist/abs.rs");
  }

  static Stream<Arguments> parsing_problems() {
    return Stream.of(
      Arguments.of(
        "Attribute 'filename' not found on 'class' element",
        xmlWithClass("""
        <class name="abs">
           <lines><line number="1" hits="1"/></lines>
        </class>
        """)),
      Arguments.of(
        "Attribute 'number' not found on 'line' element",
        xmlWithClass("""
        <class name="abs" filename="src/abs.rs">
           <lines><line hits="1" /></lines>
        </class>
        """)),
      Arguments.of(
        "Attribute 'hits' not found on 'line' element",
        xmlWithClass("""
        <class name="abs" filename="src/abs.rs">
           <lines><line number="1" /></lines>
        </class>
        """)),
      Arguments.of(
        "Invalid number format for attribute 'number' on 'line' element",
        xmlWithClass("""
        <class name="abs" filename="src/abs.rs">
           <lines><line number="foo" hits="1" /></lines>
        </class>
        """)),
      Arguments.of(
        "Invalid number format for attribute 'hits' on 'line' element",
        xmlWithClass("""
        <class name="abs" filename="src/abs.rs">
           <lines><line number="1" hits="foo" /></lines>
        </class>
        """)),
      Arguments.of(
        "Attribute 'condition-coverage' not found on 'line' element",
        xmlWithClass("""
        <class name="abs" filename="src/abs.rs">
           <lines><line number="1" hits="1" branch="true" /></lines>
        </class>
        """)),
      Arguments.of(
        "Invalid condition coverage format",
        xmlWithClass("""
        <class name="abs" filename="src/abs.rs">
           <lines><line number="1" hits="1" branch="true" condition-coverage="foo" /></lines>
        </class>
        """)),
      Arguments.of(
        "Invalid condition coverage: taken count is greater than total count",
        xmlWithClass("""
        <class name="abs" filename="src/abs.rs">
           <lines><line number="1" hits="1" branch="true" condition-coverage="100% (4/2)" /></lines>
        </class>
        """))
    );
  }

  private static String xmlWithClass(String classContent) {
    return """
      <?xml version="1.0" ?>
      <!DOCTYPE coverage SYSTEM "https://cobertura.sourceforge.net/xml/coverage-04.dtd">
      <coverage>
        <sources>
          <source>/Users/gyulasallai/projects/sandbox/rust-tests</source>
        </sources>
        <packages>
          <package>
            <classes>
              %s
            </classes>
          </package>
        </packages>
      </coverage>
    """.formatted(classContent);
  }

  private CoberturaParser.ParsingResult parse(String xml) {
    var parser = new CoberturaParser(sct, new FileLocator(sct.fileSystem().inputFiles()), "report.xml");
    return parser.parse(xml);
  }

  private SensorContextTester createSensorContext() throws IOException {
    SensorContextTester context = SensorContextTester.create(tempDir);

    context.fileSystem().add(inputFile("src/abs.rs", "abs.rs"));
    context.fileSystem().add(inputFile("src/sign.rs", "sign.rs"));
    context.fileSystem().add(inputFile("src/main.rs", "main.rs"));
    context.fileSystem().add(inputFile("src/fizzbuzz.rs", "fizzbuzz.rs"));

    return context;
  }

  private static InputFile inputFile(String pathInProject, String contentPath) throws IOException {
    return TestInputFileBuilder.create("test", pathInProject)
      .setLanguage("rust")
      .setCharset(StandardCharsets.UTF_8)
      .setContents(Files.readString(TEST_PROJECT_FILES_PATH.resolve(contentPath)))
      .build();
  }
}
