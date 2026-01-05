/*
 * SonarQube Rust Plugin
 * Copyright (C) 2025-2026 SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */
package org.sonarsource.rust.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.event.Level;
import org.sonar.api.testfixtures.log.LogTesterJUnit5;

class StreamConsumerTest {

  @RegisterExtension
  LogTesterJUnit5 logTester = new LogTesterJUnit5();

  @Test
  void shouldInvokeUncaughtExceptionHandlerWhenConsumerThrowsException() {
    // This test verifies the key fix: using execute() instead of submit()
    // With execute(), uncaught exceptions propagate to the uncaught exception handler
    // With submit(), exceptions are captured in the Future and never reach the handler

    String input = "line1\nline2\nline3";
    InputStream stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));

    StreamConsumer consumer = new StreamConsumer();

    // Consumer that throws exception (simulating ClippySensor:159 behavior)
    consumer.consumeStream(stream, line -> {
      if (line.equals("line2")) {
        throw new IllegalStateException("Test exception from consumer");
      }
    });

    // Wait for exception to be logged by uncaught exception handler
    await()
      .atMost(Duration.ofSeconds(5))
      .until(() -> logTester.logs(Level.ERROR).stream()
        .anyMatch(log -> log.contains("Error in thread stream-consumer")));

    // Verify that the exception was caught by uncaught exception handler and logged
    assertThat(logTester.logs(Level.ERROR))
      .anyMatch(log -> log.contains("Error in thread stream-consumer"));
  }
}
