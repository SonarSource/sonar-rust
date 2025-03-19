/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.clippy;

import com.sonarsource.rust.common.ProcessWrapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.event.Level;
import org.sonar.api.testfixtures.log.LogTesterJUnit5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClippyPrerequisiteTest {

  @RegisterExtension
  final LogTesterJUnit5 logTester = new LogTesterJUnit5().setLevel(Level.DEBUG);

  @Test
  void testSuccess() throws Exception {
    var process = mock(ProcessWrapper.class);
    var clippyPrerequisite = new ClippyPrerequisite(process);
    var workDir = Path.of("some/dir");

    when(process.getInputStream())
      .thenReturn(new ByteArrayInputStream("cargo 1.0.0".getBytes()))
      .thenReturn(new ByteArrayInputStream("clippy 1.0.0".getBytes()));

    clippyPrerequisite.check(workDir);

    verify(process).start(eq(List.of("cargo", "--version")), eq(workDir), any(), any());
    verify(process).start(eq(List.of("cargo", "clippy", "--version")), eq(workDir), any(), any());

    assertThat(logTester.logs()).contains(
      "Checking Cargo version",
      "Cargo version: cargo 1.0.0",
      "Checking Clippy version",
      "Clippy version: clippy 1.0.0");
  }

  @Test
  void testFailure() throws Exception {
    var processWrapper = mock(ProcessWrapper.class);
    var clippyPrerequisite = new ClippyPrerequisite(processWrapper);
    var workDir = Path.of("some/dir");

    doThrow(new IOException("error")).when(processWrapper).start(any(), any(),  any(), any());

    var exception = assertThrows(IllegalStateException.class, () -> clippyPrerequisite.check(workDir));
    assertThat(exception).hasMessageContaining("Failed to check Cargo version");
  }
}
