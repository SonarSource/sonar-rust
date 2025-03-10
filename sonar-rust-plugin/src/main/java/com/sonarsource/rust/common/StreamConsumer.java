/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class consumes stream asynchronously to avoid blocking the main thread and process getting stuck
 */
class StreamConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(StreamConsumer.class);
  private final ExecutorService executorService;

  StreamConsumer() {
    executorService = Executors.newCachedThreadPool(r -> {
      Thread thread = new Thread(r);
      thread.setName("stream-consumer");
      thread.setDaemon(true);
      thread.setUncaughtExceptionHandler((t, e) -> LOG.error("Error in thread " + t.getName(), e));
      return thread;
    });
  }

  void consumeStream(InputStream inputStream, Consumer<String> consumer) {
    executorService.submit(() -> {
      try (
        var reader = new BufferedReader(
          new InputStreamReader(inputStream, StandardCharsets.UTF_8)
        )
      ) {
        reader.lines().forEach(consumer);
      } catch (IOException e) {
        LOG.error("Error while reading stream", e);
      }
    });
  }

  void await() throws InterruptedException {
    executorService.shutdown();
    if (!executorService.awaitTermination(5, TimeUnit.MINUTES)) {
      LOG.error("External process stream consumer timed out");
      executorService.shutdownNow();
    }
  }

}
