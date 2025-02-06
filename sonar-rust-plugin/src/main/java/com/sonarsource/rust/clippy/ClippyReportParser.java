/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.clippy;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class ClippyReportParser {

  private static final Gson GSON = new Gson();

  public static List<ClippyDiagnostic> parse(File reportFile) {
    try {
      // Clippy reports are not syntactically valid JSON, because they contain one JSON object per line.
      // These objects are not separated by commas and the whole file is not enclosed in an array.
      // Therefore, we need to read the file line by line and parse each line separately.
      var lines = Files.readAllLines(reportFile.toPath());
      var diagnostics = new ArrayList<ClippyDiagnostic>();
      for (var line : lines) {
        var diagnostic = GSON.fromJson(line, ClippyDiagnostic.class);
        // Clippy reports can also contain diagnostics from the Rust compiler and whatnot.
        // As a result, we only keep diagnostics from Clippy, i.e. those that have a code starting with "clippy".
        if (diagnostic != null && diagnostic.message() != null && diagnostic.message().code().code().startsWith("clippy")) {
          diagnostics.add(diagnostic);
        }
      }
      return diagnostics;
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read Clippy report: " + reportFile, e);
    } catch (JsonSyntaxException e) {
      throw new IllegalStateException("Failed to parse Clippy report: " + reportFile, e);
    }
  }
}
