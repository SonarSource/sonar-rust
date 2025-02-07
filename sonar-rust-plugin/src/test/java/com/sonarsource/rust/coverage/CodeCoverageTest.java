/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.sonar.api.batch.fs.internal.TestInputFileBuilder;

class CodeCoverageTest {

  @Test
  void testGetInputFile() {
    var inputFile = TestInputFileBuilder.create("module", "file.rs").build();
    var coverage = new CodeCoverage(inputFile);

    assertThat(coverage.getInputFile()).isEqualTo(inputFile);
  }

  @Test
  void testAddLineHits() {
    var rustCode = """
      fn main() {
        println!("Hello, world!");
      }
      """;

    var inputFile = TestInputFileBuilder.create("module", "file.rs")
      .initMetadata("fn main() {\n  println!(\"Hello, world!\");\n}\n")
      .setContents(rustCode)
      .build();

    var coverage = new CodeCoverage(inputFile);
    coverage.addLineHits(2, 5);
    coverage.addLineHits(2, 3);

    assertThat(coverage.getLineHits()).containsEntry(2, 8);
  }

  @Test
  void testAddBranchHits() {
    var rustCode = """
      fn main() {
        let x = 42;
        if x > 0 {
          println!("Positive");
        } else {
          println!("Non-positive");
        }
      }
      """;

    var inputFile = TestInputFileBuilder.create("module", "file.rs")
      .setContents(rustCode)
      .build();

    var coverage = new CodeCoverage(inputFile);
    coverage.addBranchHits(3, "T", 1);
    coverage.addBranchHits(3, "T", 1);
    coverage.addBranchHits(3, "F", 0);

    assertThat(coverage.getBranchHits()).containsEntry(3, Map.of("T", 2, "F", 0));
  }

  @Test
  void testValidateLineNumber() {
    var rustCode = """
      fn main() {
        println!("Hey, there!");
      }
      """;

    var inputFile = TestInputFileBuilder.create("module", "file.rs")
      .setContents(rustCode)
      .build();

    var coverage = new CodeCoverage(inputFile);

    assertThatThrownBy(() -> coverage.addLineHits(0, 1))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("Line number outside file range: 0");
    assertThatThrownBy(() -> coverage.addLineHits(5, 1))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("Line number outside file range: 5");
      assertThatThrownBy(() -> coverage.addBranchHits(0, "T", 1))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("Line number outside file range: 0");
  }
}
