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
package org.sonarsource.rust.clippy;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.sonar.api.batch.fs.InputFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.sensor.SensorContext;
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
          LOG.debug("Ignoring line: {}", line);
          return false;
        }
      })
      .map(line -> GSON.fromJson(line, ClippyDiagnostic.class))
      .filter(ClippyUtils::isClippyDiagnostic);
  }

  private static boolean isClippyDiagnostic(@Nullable ClippyDiagnostic diagnostic) {
    return diagnostic != null
      && diagnostic.manifest_path() != null
      && diagnostic.message() != null
      && diagnostic.message().code() != null
      && diagnostic.message().code().code() != null
      && diagnostic.message().code().code().startsWith("clippy");
  }

  static NewIssueLocation diagnosticToLocation(NewIssueLocation location, ClippyDiagnostic diagnostic, SensorContext context) {
    var spans = diagnostic.message().spans();
    if (spans.isEmpty()) {
      throw new IllegalStateException("Empty spans");
    }

    var span = spans.get(0);
    var inputFile = resolveInputFile(diagnostic, context);
    if (inputFile == null) {
      return null;
    }

    location
      .on(inputFile)
      .at(inputFile.newRange(span.line_start(), span.column_start() - 1, span.line_end(), span.column_end() - 1));

    return location;
  }

  @Nullable
  static InputFile resolveInputFile(ClippyDiagnostic diagnostic, SensorContext context) {
    var spans = diagnostic.message().spans();
    if (spans.isEmpty()) {
      throw new IllegalStateException("Empty spans");
    }

    for (var candidate : candidatePaths(diagnostic, context)) {
      var predicates = context.fileSystem().predicates().hasPath(candidate.toString());
      var inputFile = context.fileSystem().inputFile(predicates);
      if (inputFile != null) {
        return inputFile;
      }
    }

    return null;
  }

  private static List<Path> candidatePaths(ClippyDiagnostic diagnostic, SensorContext context) {
    var spanPath = Path.of(diagnostic.message().spans().get(0).file_name()).normalize();
    var candidates = new ArrayList<Path>();
    addCandidate(candidates, spanPath);

    if (spanPath.isAbsolute()) {
      return candidates;
    }

    var baseDir = context.fileSystem().baseDir().toPath().toAbsolutePath().normalize();
    var manifestPath = Path.of(diagnostic.manifest_path());
    if (!manifestPath.isAbsolute()) {
      manifestPath = baseDir.resolve(manifestPath);
    }
    manifestPath = manifestPath.normalize();

    var manifestDir = manifestPath.getFileName() != null && "Cargo.toml".equals(manifestPath.getFileName().toString())
      ? manifestPath.getParent()
      : manifestPath;
    if (manifestDir == null) {
      return candidates;
    }

    if (!manifestDir.startsWith(baseDir)) {
      addCandidate(candidates, manifestDir.resolve(spanPath).normalize());
      return candidates;
    }

    // Try the crate directory first, then each parent up to the analysis base directory.
    for (var currentDir = manifestDir; ; currentDir = currentDir.getParent()) {
      addCandidate(candidates, currentDir.resolve(spanPath).normalize());
      if (currentDir.equals(baseDir)) {
        break;
      }
    }

    return candidates;
  }

  private static void addCandidate(List<Path> candidates, Path candidate) {
    if (!candidates.contains(candidate)) {
      candidates.add(candidate);
    }
  }
}
