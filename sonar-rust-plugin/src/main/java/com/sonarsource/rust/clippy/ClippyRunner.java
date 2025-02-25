/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.clippy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClippyRunner {

  private static final Logger LOG = LoggerFactory.getLogger(ClippyRunner.class);

  private final Path workDir;
  private final List<String> lints;

  ClippyRunner(Path workDir, List<String> lints) {
    this.workDir = workDir;
    this.lints = lints;
  }

  public List<ClippyDiagnostic> run() {
    var command = new ArrayList<>(List.of("cargo", "clippy", "--quiet", "--message-format=json", "--", "-A", "clippy::all"));
    command.addAll(lints);
    LOG.debug("Running Clippy: {}", command);
    var processBuilder = new ProcessBuilder(command)
      .directory(workDir.toFile())
      .redirectErrorStream(true);
    try {
      var process = processBuilder.start();
      var clippyDiagnostics = readOutput(process.getInputStream());
      process.waitFor();
      if (process.exitValue() != 0) {
        throw new IllegalStateException("Clippy failed with exit code " + process.exitValue());
      }
      return clippyDiagnostics;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Clippy was interrupted", e);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to run Clippy ", e);
    }
  }

  private static List<ClippyDiagnostic> readOutput(InputStream inputStream) {
    var lines = new BufferedReader(new InputStreamReader(inputStream)).lines();
    return ClippyUtils.parse(lines).toList();
  }

}
