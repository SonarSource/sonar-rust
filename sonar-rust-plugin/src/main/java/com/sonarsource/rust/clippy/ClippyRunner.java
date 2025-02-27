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

  private final ProcessWrapper processWrapper;

  ClippyRunner() {
    this(new ProcessWrapper());
  }

  ClippyRunner(ProcessWrapper processWrapper) {
    this.processWrapper = processWrapper;
  }

  public List<ClippyDiagnostic> run(Path workDir, List<String> lints) {
    var command = buildCommand(lints);
    LOG.debug("Running Clippy: {}", command);
    try {
      processWrapper.start(command, workDir);
      var clippyDiagnostics = readOutput(processWrapper.getInputStream());
      // Note that by default, Clippy can return non-zero exit code in case of a high severity lint violation. We avoid this by
      // re-defining all lints as warnings when constructing the Clippy command below.
      int exitValue = processWrapper.waitFor();
      if (exitValue != 0) {
        throw new IllegalStateException("Clippy failed with exit code " + exitValue);
      }
      return clippyDiagnostics;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Clippy was interrupted", e);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to run Clippy ", e);
    }
  }

  private static List<String> buildCommand(List<String> lints) {
    var cmd = new ArrayList<>(List.of("cargo", "clippy", "--quiet", "--message-format=json", "--", "-A", "clippy::all"));
    lints.stream().map(lint -> String.format("-W%s", lint)).forEach(cmd::add);
    return cmd;
  }

  private static List<ClippyDiagnostic> readOutput(InputStream inputStream) {
    var lines = new BufferedReader(new InputStreamReader(inputStream)).lines();
    return ClippyUtils.parse(lines).toList();
  }

}
