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
package org.sonarsource.rust.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.sonar.api.batch.sensor.SensorContext;

public class Telemetry {

  private static final String RUST_EDITION_NAME = "rust.language.edition";
  private static final String RUST_CLIPPY_EDITION_VERSION_NAME = "rust.clippy.version";
  private static final String RUST_COVERAGE_FORMAT_NAME = "rust.coverage.format";
  private static final String RUST_CLIPPY_USAGE_NAME = "rust.clippy.usage";
  private static final String RUST_DEPENDENCIES_COUNT_NAME = "rust.dependencies.count";
  private static final String RUST_DEPENDENCIES_NAME = "rust.dependencies";

  private static final Pattern CLIPPY_VERSION_PATTERN = Pattern.compile("^clippy\\s+(\\d+\\.\\d+\\.\\d+)");

  // Names of the Cargo.toml tables that declare direct dependencies. `workspace.dependencies` covers
  // virtual workspace roots, where dependencies are declared under `[workspace.dependencies]`.
  private static final Set<String> DEP_SECTIONS =
    Set.of("dependencies", "dev-dependencies", "build-dependencies", "workspace.dependencies");
  // Matches a dependency sub-table header, e.g. `dependencies.serde`, `workspace.dependencies.serde`
  // or `target.'cfg(unix)'.dependencies.libc`.
  private static final Pattern DEP_SUBTABLE_PATTERN =
    Pattern.compile("^(?:target\\..+\\.|workspace\\.)?(?:dependencies|dev-dependencies|build-dependencies)\\.(.+)$");
  // Matches `version = "1.0"` inside an inline table, e.g. `{ version = "1.0", features = [...] }`.
  private static final Pattern INLINE_VERSION_PATTERN = Pattern.compile("\\bversion\\s*=\\s*[\"']([^\"']*)[\"']");

  // The telemetry channel has practical per-analysis limits on value length, so the joined
  // dependency list is capped and truncated at a token boundary.
  private static final int MAX_VALUE_LENGTH = 1000;
  private static final String TRUNCATION_MARKER = ",...";

  private enum SectionKind {
    DEP_TABLE, DEP_SUBTABLE, OTHER
  }

  /**
   * Classification of the Cargo.toml table currently being read. {@code subtableCrate} is the crate
   * named by a `[dependencies.&lt;crate&gt;]` sub-table, and is {@code null} for the other kinds.
   */
  private record Section(SectionKind kind, @Nullable String subtableCrate) {
    static final Section OTHER = new Section(SectionKind.OTHER, null);
  }

  /**
   * A direct dependency declared in a Cargo.toml manifest. {@code version} is empty when the
   * dependency has no version requirement (git, path or workspace-inherited dependencies).
   */
  record Dependency(String name, String version) {
  }

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

  /**
   * Reports the direct dependencies declared across all the given Cargo manifests. Dependencies are
   * deduplicated and emitted as two aggregate properties: the distinct count and the comma-joined list
   * of {@code name:version} tokens (or just {@code name} when versionless). When the list would exceed
   * the channel length limit, a random sample of the dependencies is emitted instead, so the truncated
   * view is not biased toward alphabetically-early crates.
   */
  public static void reportDependencies(SensorContext context, List<Path> manifests) {
    var distinct = new TreeSet<String>();
    for (Path manifest : manifests) {
      try {
        for (Dependency dependency : parseDependencies(manifest)) {
          distinct.add(formatToken(dependency));
        }
      } catch (IOException ex) {
        // Ignore - skip this manifest and keep collecting from the others
      }
    }

    if (distinct.isEmpty()) {
      return;
    }

    saveTelemetry(context, RUST_DEPENDENCIES_COUNT_NAME, String.valueOf(distinct.size()));
    saveTelemetry(context, RUST_DEPENDENCIES_NAME, sampleDependencies(distinct));
  }

  static List<Dependency> parseDependencies(Path cargoManifest) throws IOException {
    // Preserve declaration order; the value is the version ("" when versionless).
    var deps = new LinkedHashMap<String, String>();
    Section section = Section.OTHER;

    for (var line : Files.readAllLines(cargoManifest)) {
      String trimmed = line.replaceAll("#.*", "").trim();
      if (trimmed.isEmpty()) {
        continue;
      }

      if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
        section = classifySection(trimmed.substring(1, trimmed.length() - 1).trim(), deps);
      } else {
        parseEntry(trimmed, section, deps);
      }
    }

    var result = new ArrayList<Dependency>();
    deps.forEach((name, version) -> result.add(new Dependency(name, version)));
    return result;
  }

  private static Section classifySection(String section, LinkedHashMap<String, String> deps) {
    if (isDependencyTable(section)) {
      return new Section(SectionKind.DEP_TABLE, null);
    }
    var matcher = DEP_SUBTABLE_PATTERN.matcher(section);
    if (matcher.matches()) {
      String crate = unquote(matcher.group(1).trim());
      // A sub-table may declare no version; record the crate name up front.
      deps.putIfAbsent(crate, "");
      return new Section(SectionKind.DEP_SUBTABLE, crate);
    }
    return Section.OTHER;
  }

  private static void parseEntry(String trimmed, Section section, LinkedHashMap<String, String> deps) {
    int eq = trimmed.indexOf('=');
    if (eq <= 0) {
      return;
    }
    String key = unquote(trimmed.substring(0, eq).trim());
    String version = extractVersion(trimmed.substring(eq + 1).trim());

    if (section.kind() == SectionKind.DEP_TABLE && !key.isEmpty()) {
      deps.put(key, version);
    } else if (section.kind() == SectionKind.DEP_SUBTABLE && "version".equals(key)) {
      deps.put(section.subtableCrate(), version);
    }
  }

  private static boolean isDependencyTable(String section) {
    if (DEP_SECTIONS.contains(section)) {
      return true;
    }
    // Target-specific dependencies, e.g. `target.'cfg(unix)'.dependencies`.
    return section.startsWith("target.")
      && (section.endsWith(".dependencies") || section.endsWith(".dev-dependencies") || section.endsWith(".build-dependencies"));
  }

  private static String extractVersion(String rhs) {
    if (rhs.isEmpty()) {
      return "";
    }
    char first = rhs.charAt(0);
    if (first == '"' || first == '\'') {
      return unquote(rhs);
    }
    if (first == '{') {
      // Inline table: version is optional (absent for git/path/workspace dependencies).
      var matcher = INLINE_VERSION_PATTERN.matcher(rhs);
      return matcher.find() ? matcher.group(1) : "";
    }
    // Bare token (unusual, but tolerated best-effort).
    return rhs;
  }

  private static String unquote(String value) {
    return value.replaceAll("^[\"']", "").replaceAll("[\"']$", "");
  }

  private static String formatToken(Dependency dependency) {
    // Commas would corrupt the joined list; strip them from the (rare) versions that contain them.
    String version = dependency.version().replace(",", "");
    return version.isEmpty() ? dependency.name() : ( dependency.name() + ":" + version );
  }

  /**
   * Returns the comma-joined tokens, or, when they would exceed {@link #MAX_VALUE_LENGTH}, a random
   * sample that fits within the limit (with a marker appended). Sampling shuffles first so the emitted
   * subset is not biased toward alphabetically-early crates (the set is sorted). Collections.shuffle
   * uses a shared source of randomness, so we do not instantiate a PRNG ourselves.
   */
  private static String sampleDependencies(Set<String> tokens) {
    String joined = String.join(",", tokens);
    if (joined.length() <= MAX_VALUE_LENGTH) {
      return joined;
    }
    var shuffled = new ArrayList<>(tokens);
    Collections.shuffle(shuffled);
    int budget = MAX_VALUE_LENGTH - TRUNCATION_MARKER.length();
    var sb = new StringBuilder();
    for (String token : shuffled) {
      int extra = sb.length() == 0 ? token.length() : token.length() + 1;
      if (sb.length() + extra > budget) {
        break;
      }
      if (sb.length() > 0) {
        sb.append(',');
      }
      sb.append(token);
    }
    return sb + TRUNCATION_MARKER;
  }

  private static void saveTelemetry(SensorContext context, String key, String value) {
    context.addTelemetryProperty(key, value);
  }

}
