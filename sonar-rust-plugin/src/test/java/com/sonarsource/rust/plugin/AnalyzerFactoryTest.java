/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonar.api.impl.utils.DefaultTempFolder;
import org.sonar.api.utils.TempFolder;

class AnalyzerFactoryTest {

  @TempDir
  Path temp;

  @Test
  void test() throws Exception {
    Platform platform = Platform.detect();
    assertNotEquals(Platform.UNSUPPORTED, platform);
    TempFolder tempFolder = new DefaultTempFolder(temp.toFile(), false);
    var analyzerFactory = new AnalyzerFactory(tempFolder);
    var analyzer = analyzerFactory.create(platform);
    assertNotNull(analyzer);
    assertThat(temp).isDirectoryContaining(f -> f.getFileName().toString().startsWith("analyzer-"));
  }
}
