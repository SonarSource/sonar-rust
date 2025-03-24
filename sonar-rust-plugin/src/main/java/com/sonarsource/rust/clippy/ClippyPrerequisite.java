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

  public ToolVersions check(Path workDir) {
    String cargoVersion = checkVersion(List.of("cargo", "--version"), "Cargo", workDir);
    String clippyVersion = checkVersion(List.of("cargo", "clippy", "--version"), "Clippy", workDir);

    return new ToolVersions(cargoVersion, clippyVersion);
  }

  private String checkVersion(List<String> command, String prerequisite, Path workDir) {
    LOG.debug("Checking {} version", prerequisite);
    try {
      processWrapper.start(command, workDir, null, LOG::warn);
      try (var reader = new BufferedReader(new InputStreamReader(processWrapper.getInputStream()))) {
        var version = reader.readLine();
        LOG.debug("{} version: {}", prerequisite, version);
        return version;
      }
    } catch (IOException e) {
      throw new IllegalStateException("Failed to check " + prerequisite + " version", e);
    }
  }

  public record ToolVersions(String cargoVersion, String clippyVersion) {
  }
}
