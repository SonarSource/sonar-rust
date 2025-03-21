/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import javax.annotation.CheckForNull;
import org.sonar.api.SonarEdition;
import org.sonar.api.batch.sensor.SensorContext;

public class Telemetry {

  private static final String RUST_EDITION_NAME = "rust.language.edition";
  private static final String RUST_CLIPPY_EDITION_VERSION_NAME = "rust.clippy.version";
  private static final String RUST_COVERAGE_FORMAT_NAME = "rust.coverage.format";
  private static final String RUST_CLIPPY_USAGE_NAME = "rust.clippy.usage";

  private static final Pattern CLIPPY_VERSION_PATTERN = Pattern.compile("^clippy\\s+(\\d+\\.\\d+\\.\\d+)");

  private Telemetry() {

  }

  public static void reportExternalClippyUsage(SensorContext context) {
    saveTelemetry(context, RUST_CLIPPY_USAGE_NAME, "external");
  }

  public static void reportAnalyzerClippyUsage(SensorContext context) {
    saveTelemetry(context, RUST_CLIPPY_USAGE_NAME, "sonar");
  }

  public static void reportCoverageFormat(SensorContext context, String format) {
    saveTelemetry(context, RUST_COVERAGE_FORMAT_NAME, format);
  }

  public static void reportClippyVersion(SensorContext context, String versionString) {
    var matcher = CLIPPY_VERSION_PATTERN.matcher(versionString);
    if (matcher.find()) {
      saveTelemetry(context, RUST_CLIPPY_EDITION_VERSION_NAME, matcher.group(1));
    }
  }

  public static void reportManifestInfo(SensorContext context, Path cargoManifest) {
    try {
      String edition = findRustEdition(cargoManifest);
      if (edition != null) {
        saveTelemetry(context, RUST_EDITION_NAME, edition);
      }
    } catch (IOException ex) {
      // Ignore - don't try to report anything if the manifest can't be read
    }
  }

  @CheckForNull
  private static String findRustEdition(Path cargoManifest) throws IOException {
    var lines = Files.readAllLines(cargoManifest);

    String currentSection = null;
    for (var line : lines) {
      String trimmed = line.replaceAll("#.*", "").trim();
      if (trimmed.isEmpty()) {
        continue;
      }

      if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
        currentSection = trimmed.substring(1, trimmed.length() - 1).trim();
      } else if ("package".equals(currentSection) && trimmed.startsWith("edition")) {
        var parts = trimmed.split("=");
        if (parts.length == 2) {
          return parts[1].trim().replaceAll("^[\"']", "").replaceAll("[\"']$", "");
        }
      }
    }

    return null;
  }

  private static void saveTelemetry(SensorContext context, String key, String value) {
    // TODO SKUNK-65 - Remove this check when we are ready to bundle the plugin in SQ Server
    if (context.runtime().getEdition() == SonarEdition.SONARCLOUD) {
      context.addTelemetryProperty(key, value);
    }
  }

}
