/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
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
