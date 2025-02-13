/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
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
