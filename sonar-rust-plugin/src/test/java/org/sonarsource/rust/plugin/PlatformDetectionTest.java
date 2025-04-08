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
package org.sonarsource.rust.plugin;

import static org.sonarsource.rust.plugin.PlatformDetection.Platform.LINUX_AARCH64;
import static org.sonarsource.rust.plugin.PlatformDetection.Platform.LINUX_X64_MUSL;
import static org.sonarsource.rust.plugin.PlatformDetection.Platform.UNSUPPORTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class PlatformDetectionTest {

  @Test
  void detect_linux() {
    SystemWrapper system = mock(SystemWrapper.class);
    PlatformDetection platformDetection = new PlatformDetection(system);
    when(system.getOsName()).thenReturn("Linux");
    when(system.getOsArch()).thenReturn("amd64");
    assertThat(platformDetection.detect()).isEqualTo(LINUX_X64_MUSL);
    when(system.getOsArch()).thenReturn("x86");
    assertThat(platformDetection.detect()).isEqualTo(UNSUPPORTED);
    when(system.getOsArch()).thenReturn("aarch64");
    assertThat(platformDetection.detect()).isEqualTo(LINUX_AARCH64);
  }

  @Test
  void detect_win() {
    SystemWrapper system = mock(SystemWrapper.class);
    PlatformDetection platformDetection = new PlatformDetection(system);
    when(system.getOsName()).thenReturn("Windows");
    when(system.getOsArch()).thenReturn("amd64");
    assertThat(platformDetection.detect()).isEqualTo(PlatformDetection.Platform.WIN_X64);
    when(system.getOsArch()).thenReturn("x86");
    assertThat(platformDetection.detect()).isEqualTo(UNSUPPORTED);
  }

  @Test
  void detect_macos() {
    SystemWrapper system = mock(SystemWrapper.class);
    PlatformDetection platformDetection = new PlatformDetection(system);
    when(system.getOsName()).thenReturn("Mac OS X");
    when(system.getOsArch()).thenReturn("aarch64");
    assertThat(platformDetection.detect()).isEqualTo(PlatformDetection.Platform.DARWIN_AARCH64);
    when(system.getOsArch()).thenReturn("x86_64");
    assertThat(platformDetection.detect()).isEqualTo(PlatformDetection.Platform.DARWIN_X86_64);
  }

  @Test
  void test_debug() {
    SystemWrapper system = mock(SystemWrapper.class);
    PlatformDetection platformDetection = new PlatformDetection(system);
    when(system.getOsName()).thenReturn("Linux");
    when(system.getOsArch()).thenReturn("amd64");
    assertThat(platformDetection.debug()).isEqualTo("os: Linux, arch: amd64");
  }
}
