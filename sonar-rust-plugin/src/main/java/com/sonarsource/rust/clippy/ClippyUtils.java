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
import java.util.List;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.sensor.issue.NewIssueLocation;

class ClippyUtils {

  private static final Logger LOG = LoggerFactory.getLogger(ClippyUtils.class);

  private static final Gson GSON = new Gson();

  private ClippyUtils() {
    // Utility class
  }

  static List<ClippyDiagnostic> parse(File reportFile) {
    try (var reader = Files.newBufferedReader(reportFile.toPath())) {
      // Clippy reports are not syntactically valid JSON, because they contain one JSON object per line.
      // These objects are not separated by commas and the whole file is not enclosed in an array.
      // Therefore, we need to read the file line by line and parse each line separately.
      var lines = reader.lines();
      return parse(lines).toList();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read Clippy report: " + reportFile, e);
    } catch (JsonSyntaxException e) {
      throw new IllegalStateException("Failed to parse Clippy report: " + reportFile, e);
    }
  }

  static Stream<ClippyDiagnostic> parse(Stream<String> lines) {
    return lines
      .filter(line -> {
        if (line.startsWith("{")) {
          return true;
        } else {
          LOG.debug("Skipped output: {}", line);
          return false;
        }
      })
      .map(line -> GSON.fromJson(line, ClippyDiagnostic.class))
      .filter(ClippyUtils::isClippyDiagnostic);
  }

  private static boolean isClippyDiagnostic(@Nullable ClippyDiagnostic diagnostic) {
    return diagnostic != null
      && diagnostic.message() != null
      && diagnostic.message().code() != null
      && diagnostic.message().code().code() != null
      && diagnostic.message().code().code().startsWith("clippy");
  }

  static NewIssueLocation diagnosticToLocation(NewIssueLocation location, ClippyDiagnostic diagnostic, FileSystem fs) {
    var spans = diagnostic.message().spans();
    if (spans.isEmpty()) {
      throw new IllegalStateException("Empty spans");
    }

    var span = spans.get(0);
    var fileName = span.file_name();

    var predicates = fs.predicates().hasPath(fileName);
    var inputFile = fs.inputFile(predicates);
    if (inputFile == null) {
      return null;
    }
    location
      .on(inputFile)
      .at(inputFile.newRange(span.line_start(), span.column_start() - 1, span.line_end(), span.column_end() - 1))
      .message(diagnostic.message().message());

    return location;
  }
}
