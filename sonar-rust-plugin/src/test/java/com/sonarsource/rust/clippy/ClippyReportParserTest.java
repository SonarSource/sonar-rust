/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.clippy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonSyntaxException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class ClippyReportParserTest {

  @Test
  void testParseValidReport() throws IOException {
    var json = """
      {"message": {"code": {"code": "clippy::some_lint"}}}
      {"message": {"code": {"code": "clippy::some_other_lint"}}}
      """;
    var tempFile = Files.createTempFile("clippy_report", ".json");
    Files.writeString(tempFile, json);

    var diagnostics = ClippyReportParser.parse(tempFile.toFile());

    assertThat(diagnostics).hasSize(2);
    assertThat(diagnostics.get(0).message().code().code()).isEqualTo("clippy::some_lint");

    Files.delete(tempFile);
  }

  @Test
  void testParseInvalidJson() throws IOException {
    var json = """
      {"message": {"code": {"code": "clippy::some_lint"}
      """;
    var tempFile = Files.createTempFile("clippy_report", ".json");
    Files.writeString(tempFile, json);

    assertThatThrownBy(() -> ClippyReportParser.parse(tempFile.toFile()))
      .isInstanceOf(IllegalStateException.class)
      .hasCauseInstanceOf(JsonSyntaxException.class)
      .hasMessageContaining("Failed to parse Clippy report");

    Files.delete(tempFile);
  }

  @Test
  void testParseNonClippyCode() throws IOException {
    var json = """
      {"message": {"code": {"code": "rustc::some_lint"}}}
      """;
    var tempFile = Files.createTempFile("clippy_report", ".json");
    Files.writeString(tempFile, json);

    var diagnostics = ClippyReportParser.parse(tempFile.toFile());

    assertThat(diagnostics).isEmpty();

    Files.delete(tempFile);
  }

  @Test
  void testParseMissingMessage() throws IOException {
    var json = """
      {"reason": "build-finished", "success": true}
      """;
    var tempFile = Files.createTempFile("clippy_report", ".json");
    Files.writeString(tempFile, json);

    var diagnostics = ClippyReportParser.parse(tempFile.toFile());

    assertThat(diagnostics).isEmpty();

    Files.delete(tempFile);
  }

  @Test
  void testParseEmptyReport() throws IOException {
    var tempFile = Files.createTempFile("clippy_report", ".json");

    var diagnostics = ClippyReportParser.parse(tempFile.toFile());

    assertThat(diagnostics).isEmpty();

    Files.delete(tempFile);
  }

  @Test
  void testParseNonExistentReport() {
    var nonExistentFile = new File("non_existent_file.json");

    assertThatThrownBy(() -> ClippyReportParser.parse(nonExistentFile))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("Failed to read Clippy report");
  }
}
