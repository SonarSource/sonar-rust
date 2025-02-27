/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.clippy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * This class is a wrapper around {@link Process} to make it easier to test. It should not contain any logic, only delegation to Java APIs.
 * It's not expected to be tested, and have full coverage.
 */
class ProcessWrapper {

  private Process process;

  void start(List<String> command, Path workDir) throws IOException {
    var pb = new ProcessBuilder(command)
      .directory(workDir.toFile());
    process = pb.start();
  }

  InputStream getInputStream() {
    return process.getInputStream();
  }

  int waitFor() throws InterruptedException {
    return process.waitFor();
  }

}
