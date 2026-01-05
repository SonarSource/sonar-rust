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
package org.sonarsource.rust.coverage;

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
      .hasMessage("Line number outside of file range: 0");
    assertThatThrownBy(() -> coverage.addLineHits(5, 1))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("Line number outside of file range: 5");
      assertThatThrownBy(() -> coverage.addBranchHits(0, "T", 1))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("Line number outside of file range: 0");
  }
}
