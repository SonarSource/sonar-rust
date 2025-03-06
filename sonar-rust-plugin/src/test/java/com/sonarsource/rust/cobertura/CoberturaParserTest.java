/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.cobertura;

import com.sonarsource.rust.common.FileLocator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonar.api.batch.fs.internal.TestInputFileBuilder;
import org.sonar.api.batch.sensor.internal.SensorContextTester;
import org.xml.sax.SAXException;

import static org.assertj.core.api.Assertions.assertThat;

class CoberturaParserTest {

  @TempDir
  Path tempDir;

  @Test
  void parse() throws IOException, SAXException {
    SensorContextTester sct = SensorContextTester.create(tempDir);

    sct.fileSystem().add(TestInputFileBuilder.create("test", "src/abs.rs")
      .setLanguage("rust")
      .setCharset(StandardCharsets.UTF_8)
      .setContents(Files.readString(Paths.get("src", "test", "resources", "cobertura", "abs.rs")))
      .build());

    sct.fileSystem().add(TestInputFileBuilder.create("test", "src/sign.rs")
      .setLanguage("rust")
      .setCharset(StandardCharsets.UTF_8)
      .setContents(Files.readString(Paths.get("src", "test", "resources", "cobertura", "sign.rs")))
      .build());

    sct.fileSystem().add(TestInputFileBuilder.create("test", "src/main.rs")
      .setLanguage("rust")
      .setCharset(StandardCharsets.UTF_8)
      .setContents(Files.readString(Paths.get("src", "test", "resources", "cobertura", "main.rs")))
      .build());

    sct.fileSystem().add(TestInputFileBuilder.create("test", "src/fizzbuzz.rs")
      .setLanguage("rust")
      .setCharset(StandardCharsets.UTF_8)
      .setContents(Files.readString(Paths.get("src", "test", "resources", "cobertura", "fizzbuzz.rs")))
      .build()
    );

    var parser = new CoberturaParser(sct, Paths.get("src", "test", "resources", "cobertura", "cobertura.xml").toFile(),
      new FileLocator(sct.fileSystem().inputFiles()));
    var result = parser.parse();

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
}
