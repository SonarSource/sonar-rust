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
package org.sonarsource.rust.plugin;

import org.sonarsource.rust.plugin.PlatformDetection.Platform;
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
    assertThat(AnalyzerFactory.pathInJar(Platform.WIN_X64)).isEqualTo("/analyzer/win-x64/analyzer.exe.xz");
    assertThat(AnalyzerFactory.pathInJar(Platform.LINUX_X64_MUSL)).isEqualTo("/analyzer/linux-x64-musl/analyzer.xz");
    assertThat(AnalyzerFactory.pathInJar(Platform.LINUX_AARCH64)).isEqualTo("/analyzer/linux-aarch64-musl/analyzer.xz");
    assertThat(AnalyzerFactory.pathInJar(Platform.DARWIN_AARCH64)).isEqualTo("/analyzer/darwin-aarch64/analyzer.xz");
    assertThat(AnalyzerFactory.pathInJar(Platform.DARWIN_X86_64)).isEqualTo("/analyzer/darwin-x86_64/analyzer.xz");
    assertThrows(IllegalStateException.class, () -> AnalyzerFactory.pathInJar(Platform.UNSUPPORTED));
  }
}
