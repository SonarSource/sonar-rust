/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import static com.sonarsource.rust.plugin.PlatformDetection.Platform.LINUX_AARCH64;
import static com.sonarsource.rust.plugin.PlatformDetection.Platform.LINUX_X64;
import static com.sonarsource.rust.plugin.PlatformDetection.Platform.LINUX_X64_MUSL;
import static com.sonarsource.rust.plugin.PlatformDetection.Platform.UNSUPPORTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PlatformDetectionTest {

  @Test
  void detect_linux() {
    SystemWrapper system = mock(SystemWrapper.class);
    PlatformDetection platformDetection = new PlatformDetection(system);
    when(system.getOsName()).thenReturn("Linux");
    when(system.getOsArch()).thenReturn("amd64");
    assertThat(platformDetection.detect()).isEqualTo(LINUX_X64);
    when(system.getOsArch()).thenReturn("x86");
    assertThat(platformDetection.detect()).isEqualTo(UNSUPPORTED);
    when(system.getOsArch()).thenReturn("aarch64");
    assertThat(platformDetection.detect()).isEqualTo(LINUX_AARCH64);

    when(system.fileExists(Path.of("/etc/alpine-release"))).thenReturn(true);
    assertThat(platformDetection.detect()).isEqualTo(LINUX_AARCH64);
    when(system.getOsArch()).thenReturn("x86");
    assertThat(platformDetection.detect()).isEqualTo(UNSUPPORTED);
    when(system.getOsArch()).thenReturn("amd64");
    assertThat(platformDetection.detect()).isEqualTo(LINUX_X64_MUSL);
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
