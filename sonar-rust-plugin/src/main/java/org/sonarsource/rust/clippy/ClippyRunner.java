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
package org.sonarsource.rust.clippy;

import org.sonarsource.rust.common.ProcessWrapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
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

  public void run(Path workDir, List<String> lints, Consumer<ClippyDiagnostic> consumer, boolean offline) {
    var command = buildCommand(lints, offline);
    LOG.debug("Running Clippy: {}", command);
    try {
      processWrapper.start(command, workDir, output -> readOutput(output, consumer), LOG::warn);
      // Note that by default, Clippy can return non-zero exit code in case of a high severity lint violation. We avoid this by
      // re-defining all lints as warnings when constructing the Clippy command below.
      int exitValue = processWrapper.waitFor();
      if (exitValue != 0) {
        throw new IllegalStateException("Clippy failed with exit code " + exitValue);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Clippy was interrupted", e);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to run Clippy ", e);
    }
  }

  private static List<String> buildCommand(List<String> lints, boolean offline) {
    var cmd = new ArrayList<>(List.of("cargo", "clippy", "--quiet", "--message-format=json"));
    if (offline) {
      cmd.add("--offline");
    }
    cmd.addAll(List.of("--", "-A", "clippy::all"));
    lints.stream().map(lint -> String.format("-W%s", lint)).forEach(cmd::add);
    return cmd;
  }

  private static void readOutput(String output, Consumer<ClippyDiagnostic> consumer) {
    ClippyUtils.parse(Stream.of(output)).forEach(consumer);
  }

}
