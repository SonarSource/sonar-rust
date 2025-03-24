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
package com.sonarsource.rust.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * This class is a wrapper around {@link Process} to make it easier to test. It should not contain any logic, only delegation to Java APIs.
 * It's not expected to be tested, and have full coverage.
 */
public class ProcessWrapper {

  private Process process;
  private StreamConsumer consumer;

  public void start(List<String> command, @Nullable Path workDir, @Nullable Consumer<String> stdOut, @Nullable Consumer<String> stdErr) throws IOException {
    var pb = new ProcessBuilder(command)
      .directory(workDir != null ? workDir.toFile() : null);
    process = pb.start();
    consumer = new StreamConsumer();
    if (stdOut != null) {
      consumer.consumeStream(process.getInputStream(), stdOut);
    }
    if (stdErr != null) {
      consumer.consumeStream(process.getErrorStream(), stdErr);
    }
  }

  public InputStream getInputStream() {
    return process.getInputStream();
  }

  public OutputStream getOutputStream() {
    return process.getOutputStream();
  }

  public int waitFor() throws InterruptedException {
    int exitValue = process.waitFor();
    consumer.await();
    return exitValue;
  }

  public void destroyForcibly() {
    process.destroyForcibly();
    try {
      consumer.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for the process to finish", e);
    }
  }

}
