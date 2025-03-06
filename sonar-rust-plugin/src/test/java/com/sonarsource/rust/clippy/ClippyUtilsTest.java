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
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClippyUtilsTest {

  @TempDir
  Path temp;

  @Test
  void testParseValidReport() throws IOException {
    var tempFile = prepareTempReportFile("""
      {"message": {"code": {"code": "clippy::some_lint"}}}
      {"message": {"code": {"code": "clippy::some_other_lint"}}}
      """);

    var diagnostics = ClippyUtils.parse(tempFile.toFile());

    assertThat(diagnostics).hasSize(2);
    assertThat(diagnostics.get(0).message().code().code()).isEqualTo("clippy::some_lint");
  }

  @Test
  void testParseInvalidJson() throws IOException {
    var tempFile = prepareTempReportFile("""
      {"message": {"code": {"code": "clippy::some_lint"}
      """);

    assertThatThrownBy(() -> ClippyUtils.parse(tempFile.toFile()))
      .isInstanceOf(IllegalStateException.class)
      .hasCauseInstanceOf(JsonSyntaxException.class)
      .hasMessageContaining("Failed to parse Clippy report");
  }

  @Test
  void testParseNonClippyCode() throws IOException {
    var tempFile = prepareTempReportFile("""
      {"message": {"code": {"code": "rustc::some_lint"}}}
      """);

    var diagnostics = ClippyUtils.parse(tempFile.toFile());

    assertThat(diagnostics).isEmpty();
  }

  @Test
  void testParseMissingMessage() throws IOException {
    var tempFile = prepareTempReportFile("""
      {"reason": "build-finished", "success": true}
      """);

    var diagnostics = ClippyUtils.parse(tempFile.toFile());

    assertThat(diagnostics).isEmpty();
  }

  @Test
  void testParseEmptyJson() throws IOException {
    var tempFile = prepareTempReportFile("""
      {}
      """);

    var diagnostics = ClippyUtils.parse(tempFile.toFile());

    assertThat(diagnostics).isEmpty();
  }

  private Path prepareTempReportFile(String json) throws IOException {
    var tempFile = Files.createTempFile(temp, "clippy_report", ".json");
    Files.writeString(tempFile, json);
    return tempFile;
  }

  @Test
  void testParseEmptyReport() throws IOException {
    var tempFile = Files.createTempFile("clippy_report", ".json");

    var diagnostics = ClippyUtils.parse(tempFile.toFile());

    assertThat(diagnostics).isEmpty();
  }

  @Test
  void testParseNonExistentReport() {
    var nonExistentFile = new File("non_existent_file.json");

    assertThatThrownBy(() -> ClippyUtils.parse(nonExistentFile))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("Failed to read Clippy report");
  }

  @Test
  void parseNonClippyDiagnostic() throws IOException {
    var diagnostics = ClippyUtils.parse(Arrays.stream("""
      {"message": {"code": {}}}
      {"message": {"code": {"code": "clippy::some_lint"}}}
      """.split("\n")));
    assertThat(diagnostics).hasSize(1);
  }
}
