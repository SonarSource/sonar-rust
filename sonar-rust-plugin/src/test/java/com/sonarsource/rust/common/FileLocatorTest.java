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
package com.sonarsource.rust.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.sonar.api.batch.fs.internal.TestInputFileBuilder;

class FileLocatorTest {

  @Test
  void testMatchRelativePath() {
    var inputFile = new TestInputFileBuilder("module", "path/to/file.rs").build();
    var locator = new FileLocator(Collections.singleton(inputFile));

    assertThat(locator.getInputFile("path/to/file.rs")).isEqualTo(inputFile);
  }

  @Test
  void testMatchFileName() {
    var inputFile = new TestInputFileBuilder("module", "file.rs").build();
    var locator = new FileLocator(Collections.singleton(inputFile));

    assertThat(locator.getInputFile("file.rs")).isEqualTo(inputFile);
  }

  @Test
  void testMatchFirst() {
    var inputFile1 = new TestInputFileBuilder("module", "path/to/file.rs").build();
    var inputFile2 = new TestInputFileBuilder("module", "path/to/file.rs").build();

    var locator = new FileLocator(Arrays.asList(inputFile1, inputFile2));

    assertThat(locator.getInputFile("path/to/file.rs")).isEqualTo(inputFile1);
  }

  @Test
  void testNoMatch() {
    var inputFile = new TestInputFileBuilder("module", "path/to/file.rs").build();
    var locator = new FileLocator(Collections.singleton(inputFile));

    assertThat(locator.getInputFile("path/to/file1.rs")).isNull();
    assertThat(locator.getInputFile("another/path/to/file.rs")).isNull();
  }
}
