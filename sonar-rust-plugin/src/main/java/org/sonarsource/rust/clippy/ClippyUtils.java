/*
 * SonarQube Rust Plugin
 * Copyright (C) SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * You can redistribute and/or modify this program under the terms of
 * the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
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
import java.util.LinkedHashSet;
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
    var span = firstSpan(diagnostic);
    var inputFile = resolveInputFile(diagnostic, context);
    if (inputFile == null) {
      return null;
    }

    try {
      location
        .on(inputFile)
        .at(inputFile.newRange(span.line_start(), span.column_start() - 1, span.line_end(), span.column_end() - 1));
    } catch (RuntimeException e) {
      throw ClippyImportException.invalidDiagnostic("Invalid location for file: " + span.file_name(), e);
    }

    return location;
  }

  @Nullable
  static InputFile resolveInputFile(ClippyDiagnostic diagnostic, SensorContext context) {
    firstSpan(diagnostic);

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
    var spanPath = spanPath(diagnostic);
    if (spanPath.isAbsolute()) {
      return List.of(spanPath);
    }

    var baseDir = context.fileSystem().baseDir().toPath().toAbsolutePath().normalize();
    var manifestPath = manifestPath(diagnostic, baseDir);

    var manifestDir = manifestPath.endsWith("Cargo.toml")
      ? manifestPath.getParent()
      : manifestPath;
    if (manifestDir == null) {
      throw ClippyImportException.invalidDiagnostic("Invalid manifest path: " + diagnostic.manifest_path());
    }

    var candidates = new LinkedHashSet<Path>();
    if (!manifestDir.startsWith(baseDir)) {
      candidates.add(spanPath);
      candidates.add(manifestDir.resolve(spanPath).normalize());
      return new ArrayList<>(candidates);
    }

    // Try the crate directory first, then each parent up to the analysis base directory.
    for (var currentDir = manifestDir; ; currentDir = currentDir.getParent()) {
      candidates.add(currentDir.resolve(spanPath).normalize());
      if (currentDir.equals(baseDir)) {
        break;
      }
    }

    return new ArrayList<>(candidates);
  }

  private static Path spanPath(ClippyDiagnostic diagnostic) {
    var fileName = firstSpan(diagnostic).file_name();
    if (fileName == null || fileName.isBlank()) {
      throw ClippyImportException.invalidDiagnostic("Clippy diagnostic '%s' has no file path".formatted(diagnostic.lintId()));
    }

    try {
      return Path.of(fileName).normalize();
    } catch (RuntimeException e) {
      throw ClippyImportException.invalidDiagnostic("Invalid file path: " + fileName, e);
    }
  }

  private static Path manifestPath(ClippyDiagnostic diagnostic, Path baseDir) {
    try {
      var manifestPath = Path.of(diagnostic.manifest_path());
      if (!manifestPath.isAbsolute()) {
        manifestPath = baseDir.resolve(manifestPath);
      }
      return manifestPath.normalize();
    } catch (RuntimeException e) {
      throw ClippyImportException.invalidDiagnostic("Invalid manifest path: " + diagnostic.manifest_path(), e);
    }
  }

  private static ClippySpan firstSpan(ClippyDiagnostic diagnostic) {
    var spans = diagnostic.message().spans();
    if (spans == null || spans.isEmpty()) {
      throw ClippyImportException.invalidDiagnostic("Clippy diagnostic '%s' has no location spans".formatted(diagnostic.lintId()));
    }
    return spans.get(0);
  }
}
