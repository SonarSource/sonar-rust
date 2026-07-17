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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.sonar.api.SonarEdition;
import org.sonar.api.SonarQubeSide;
import org.sonar.api.SonarRuntime;
import com.sonarsource.scanner.engine.sensor.test.fixtures.SensorContextTester;
import com.sonarsource.scanner.engine.sensor.test.fixtures.TestSonarRuntime;
import org.sonar.api.utils.Version;

class TelemetryTest {

  @TempDir
  private Path tmpDir;

  private static final SonarRuntime SONAR_RUNTIME = TestSonarRuntime.forSonarQube(Version.create(10, 14), SonarQubeSide.SCANNER, SonarEdition.SONARCLOUD);

  @ParameterizedTest(name = "{0}")
  @MethodSource("manifestFiles")
  void report_manifest_info(String name, @Nullable String expected, String manifestContent) throws IOException {
    var manifest = tmpDir.resolve("Cargo.toml");
    Files.writeString(manifest, manifestContent);

    SensorContextTester sct = spy(SensorContextTester.create(tmpDir).setRuntime(SONAR_RUNTIME));

    Telemetry.reportManifestInfo(sct, manifest);

    if (expected != null) {
      verify(sct).addTelemetryProperty("rust.language.edition", expected);
    } else {
      verify(sct, never()).addTelemetryProperty(Mockito.anyString(), Mockito.anyString());
    }
  }

  @Test
  void report_manifest_info_io_error() {
    var manifest = tmpDir.resolve("noSuchCargo.toml");
    SensorContextTester sct = spy(SensorContextTester.create(tmpDir).setRuntime(SONAR_RUNTIME));

    Telemetry.reportManifestInfo(sct, manifest);

    verify(sct, never()).addTelemetryProperty(Mockito.anyString(), Mockito.anyString());
  }

  @ParameterizedTest
  @CsvSource(nullValues = "null", delimiterString = ";", value = {
    "clippy 0.1.83 (90b35a62 2024-11-26);0.1.83",
    "clippy 0.1.83;0.1.83",
    "clippy;null",
    "cargo 0.1.83;null",
    "lorem ipsum clippy 0.1.83;null",
    "\"\";null"
  })
  void report_clippy_version(String versionString, @Nullable String expected) {
    SensorContextTester sct = spy(SensorContextTester.create(tmpDir).setRuntime(SONAR_RUNTIME));
    Telemetry.reportClippyVersion(sct, versionString);

    if (expected != null) {
      verify(sct).addTelemetryProperty("rust.clippy.version", expected);
    } else {
      verify(sct, never()).addTelemetryProperty(Mockito.anyString(), Mockito.anyString());
    }
  }

  static Stream<Arguments> manifestFiles() {
    return Stream.of(
      Arguments.of("valid manifest", "2021", """
        [package]
        name = "analyzer"
        version = "0.1.0"
        edition = "2021"

        [dependencies]
        tree-sitter = "0.25.1"
        tree-sitter-rust = "0.23.2"
        """),
      Arguments.of("commented edition","2021", """
        [package]
        # edition = "2018"
        edition = "2021"
        """),
      Arguments.of("single quotes", "2021", """
        [package]
        name = "analyzer"
        version = "0.1.0"
        edition = '2021'
        """),
      Arguments.of("no quotes", "2021", """
        [package]
        edition=2021
        """),
      Arguments.of("package section with comments", "2021", """
        [package] # This is the package
        name = "analyzer"
        version = "0.1.0"
        edition = "2021"
        """),
      Arguments.of("package section with whitespace", "2021", """
        [   package ]
        name = "analyzer"
        version = "0.1.0"
        edition = "2021"
        """),
      Arguments.of("invalid package section", null, """
        [package
        name = "analyzer"
        version = "0.1.0"
        edition = "2021"
        """),
        Arguments.of("no edition set", null, """
        [package]
        name = "analyzer"
        version = "0.1.0"
        """),
      Arguments.of("empty file", null, ""),
      Arguments.of("empty edition", null, """
        [package]
        edition =
        """),
      Arguments.of("edition not in package section", null, """
        [package]
        name = "analyzer"
        version = "0.1.0"
        [dependencies]
        edition = "0.25.1"
        """)
      );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("dependencyManifests")
  void report_dependencies(String name, @Nullable String expectedList, @Nullable String expectedCount, String manifestContent) throws IOException {
    var manifest = tmpDir.resolve("Cargo.toml");
    Files.writeString(manifest, manifestContent);

    SensorContextTester sct = spy(SensorContextTester.create(tmpDir).setRuntime(SONAR_RUNTIME));

    Telemetry.reportDependencies(sct, List.of(manifest));

    if (expectedList != null) {
      verify(sct).addTelemetryProperty("rust.dependencies", expectedList);
      verify(sct).addTelemetryProperty("rust.dependencies.count", expectedCount);
    } else {
      verify(sct, never()).addTelemetryProperty(Mockito.anyString(), Mockito.anyString());
    }
  }

  @Test
  void report_dependencies_deduplicates_across_manifests() throws IOException {
    var m1 = tmpDir.resolve("a/Cargo.toml");
    var m2 = tmpDir.resolve("b/Cargo.toml");
    Files.createDirectories(m1.getParent());
    Files.createDirectories(m2.getParent());
    Files.writeString(m1, """
      [dependencies]
      serde = "1.0"
      rand = "0.8"
      """);
    Files.writeString(m2, """
      [dependencies]
      serde = "1.0"
      tokio = "1.38"
      """);

    SensorContextTester sct = spy(SensorContextTester.create(tmpDir).setRuntime(SONAR_RUNTIME));

    Telemetry.reportDependencies(sct, List.of(m1, m2));

    verify(sct).addTelemetryProperty("rust.dependencies", "rand:0.8,serde:1.0,tokio:1.38");
    verify(sct).addTelemetryProperty("rust.dependencies.count", "3");
  }

  @Test
  void report_dependencies_io_error() {
    var manifest = tmpDir.resolve("noSuchCargo.toml");
    SensorContextTester sct = spy(SensorContextTester.create(tmpDir).setRuntime(SONAR_RUNTIME));

    Telemetry.reportDependencies(sct, List.of(manifest));

    verify(sct, never()).addTelemetryProperty(Mockito.anyString(), Mockito.anyString());
  }

  @Test
  void report_dependencies_truncates_long_list() throws IOException {
    var content = new StringBuilder("[dependencies]\n");
    for (int i = 0; i < 500; i++) {
      content.append(String.format("crate_%04d = \"1.0.0\"%n", i));
    }
    var manifest = tmpDir.resolve("Cargo.toml");
    Files.writeString(manifest, content.toString());

    SensorContextTester sct = spy(SensorContextTester.create(tmpDir).setRuntime(SONAR_RUNTIME));

    Telemetry.reportDependencies(sct, List.of(manifest));

    var listCaptor = ArgumentCaptor.forClass(String.class);
    verify(sct).addTelemetryProperty(Mockito.eq("rust.dependencies"), listCaptor.capture());
    assertThat(listCaptor.getValue()).hasSizeLessThanOrEqualTo(1000).endsWith(",...");
    // The count reflects the true total even though the list value is truncated.
    verify(sct).addTelemetryProperty("rust.dependencies.count", "500");
  }

  static Stream<Arguments> dependencyManifests() {
    return Stream.of(
      Arguments.of("simple string dependencies", "serde:1.0.190,tree-sitter:0.25.1", "2", """
        [package]
        name = "analyzer"
        [dependencies]
        tree-sitter = "0.25.1"
        serde = "1.0.190"
        """),
      Arguments.of("inline table with version and features", "serde:1.0", "1", """
        [dependencies]
        serde = { version = "1.0", features = ["derive"] }
        """),
      Arguments.of("git dependency without version", "rand", "1", """
        [dependencies]
        rand = { git = "https://github.com/rust-random/rand" }
        """),
      Arguments.of("path dependency without version", "foo", "1", """
        [dependencies]
        foo = { path = "../foo" }
        """),
      Arguments.of("workspace-inherited dependency", "bar", "1", """
        [dependencies]
        bar = { workspace = true }
        """),
      Arguments.of("sub-table with version", "serde:1.0", "1", """
        [dependencies.serde]
        version = "1.0"
        features = ["derive"]
        """),
      Arguments.of("sub-table without version", "local", "1", """
        [dependencies.local]
        path = "../local"
        """),
      Arguments.of("dev and build dependencies", "cc:1.0,mockito:1.0,serde:1.0", "3", """
        [dependencies]
        serde = "1.0"
        [dev-dependencies]
        mockito = "1.0"
        [build-dependencies]
        cc = "1.0"
        """),
      Arguments.of("target-specific dependencies", "libc:0.2,winapi:0.3", "2", """
        [target.'cfg(unix)'.dependencies]
        libc = "0.2"
        [target.'cfg(windows)'.dependencies.winapi]
        version = "0.3"
        """),
      Arguments.of("workspace dependencies table", "serde:1.0,tokio:1.38", "2", """
        [workspace]
        members = ["a"]
        [workspace.dependencies]
        serde = "1.0"
        tokio = { version = "1.38", features = ["full"] }
        """),
      Arguments.of("workspace dependency sub-table", "serde:1.0", "1", """
        [workspace.dependencies.serde]
        version = "1.0"
        features = ["derive"]
        """),
      Arguments.of("dotted key in dependency table", "serde:1.0", "1", """
        [dependencies]
        serde.version = "1.0"
        serde.features = ["derive"]
        """),
      Arguments.of("non-dependency sections ignored", null, null, """
        [package]
        name = "analyzer"
        edition = "2021"
        [features]
        default = ["serde"]
        [profile.release]
        opt-level = 3
        [workspace]
        members = ["a"]
        [package.metadata]
        foo = "bar"
        """),
      Arguments.of("comments ignored", "serde:1.0", "1", """
        [dependencies]
        serde = "1.0" # the serde crate
        # rand = "0.8"
        """),
      Arguments.of("quoted crate name", "tree-sitter:0.25.1", "1", """
        [dependencies]
        "tree-sitter" = "0.25.1"
        """),
      Arguments.of("empty file", null, null, ""),
      Arguments.of("empty dependencies table", null, null, """
        [package]
        name = "analyzer"
        [dependencies]
        """),
      Arguments.of("malformed dependencies header", null, null, """
        [dependencies
        serde = "1.0"
        """)
      );
  }

}
