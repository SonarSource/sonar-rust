/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import com.sonarsource.rust.plugin.PlatformDetection.Platform;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonar.api.impl.utils.DefaultTempFolder;
import org.sonar.api.utils.TempFolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalyzerFactoryTest {

  @TempDir
  Path temp;

  @Test
  void test() throws Exception {
    Platform platform = new PlatformDetection().detect();
    assertNotEquals(Platform.UNSUPPORTED, platform);
    TempFolder tempFolder = new DefaultTempFolder(temp.toFile(), false);
    var analyzerFactory = new AnalyzerFactory(tempFolder);
    try (var analyzer = analyzerFactory.create(platform)) {
      assertNotNull(analyzer);
      assertThat(temp).isDirectoryContaining(f -> f.getFileName().toString().startsWith("analyzer-"));
    }
  }

  @Test
  void testPathInJar() {
    assertThat(AnalyzerFactory.pathInJar(Platform.WIN_X64)).isEqualTo("/analyzer/win-x64/analyzer.exe");
    assertThat(AnalyzerFactory.pathInJar(Platform.LINUX_X64)).isEqualTo("/analyzer/linux-x64/analyzer");
    assertThat(AnalyzerFactory.pathInJar(Platform.LINUX_X64_MUSL)).isEqualTo("/analyzer/linux-x64-musl/analyzer");
    assertThat(AnalyzerFactory.pathInJar(Platform.LINUX_AARCH64)).isEqualTo("/analyzer/linux-aarch64-musl/analyzer");
    assertThat(AnalyzerFactory.pathInJar(Platform.DARWIN_AARCH64)).isEqualTo("/analyzer/darwin-aarch64/analyzer");
    assertThat(AnalyzerFactory.pathInJar(Platform.DARWIN_X86_64)).isEqualTo("/analyzer/darwin-x86_64/analyzer");
    assertThrows(IllegalStateException.class, () -> AnalyzerFactory.pathInJar(Platform.UNSUPPORTED));
  }
}
