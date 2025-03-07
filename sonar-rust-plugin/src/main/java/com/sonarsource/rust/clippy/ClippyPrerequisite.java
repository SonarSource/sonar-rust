/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.clippy;

import com.sonarsource.rust.common.ProcessWrapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClippyPrerequisite {

  private static final Logger LOG = LoggerFactory.getLogger(ClippyPrerequisite.class);

  private final ProcessWrapper processWrapper;

  public ClippyPrerequisite() {
    this(new ProcessWrapper());
  }

  public ClippyPrerequisite(ProcessWrapper processWrapper) {
    this.processWrapper = processWrapper;
  }

  public void check(Path workDir) {
    checkVersion(List.of("cargo", "--version"), "Cargo", workDir);
    checkVersion(List.of("cargo", "clippy", "--version"), "Clippy", workDir);
  }

  private void checkVersion(List<String> command, String prerequisite, Path workDir) {
    LOG.debug("Checking {} version", prerequisite);
    try {
      processWrapper.start(command, workDir, LOG::debug, LOG::warn);
      try (var reader = new BufferedReader(new InputStreamReader(processWrapper.getInputStream()))) {
        var version = reader.readLine();
        LOG.debug("{} version: {}", prerequisite, version);
      }
    } catch (IOException e) {
      throw new IllegalStateException("Failed to check " + prerequisite + " version", e);
    }
  }
}
