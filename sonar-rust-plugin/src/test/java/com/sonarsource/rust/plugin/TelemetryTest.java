/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.sonar.api.SonarEdition;
import org.sonar.api.SonarQubeSide;
import org.sonar.api.SonarRuntime;
import org.sonar.api.batch.sensor.internal.SensorContextTester;
import org.sonar.api.internal.SonarRuntimeImpl;
import org.sonar.api.utils.Version;

class TelemetryTest {

  @TempDir
  private Path tmpDir;

  private static final SonarRuntime SONAR_RUNTIME = SonarRuntimeImpl.forSonarQube(Version.create(10, 14), SonarQubeSide.SCANNER, SonarEdition.SONARCLOUD);

  @Test
  void telemetry_not_reported_on_sonarqube_server() {
    // TODO SKUNK-65 - Remove this check when we are ready to bundle the plugin in SQ Server
    SensorContextTester sct = Mockito.spy(SensorContextTester.create(tmpDir).setRuntime(SonarRuntimeImpl.forSonarQube(Version.create(10, 14), SonarQubeSide.SCANNER, SonarEdition.DEVELOPER)));
    Telemetry.reportExternalClippyUsage(sct);

    Mockito.verify(sct, Mockito.never()).addTelemetryProperty(Mockito.anyString(), Mockito.anyString());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("manifestFiles")
  void report_manifest_info(String name, @Nullable String expected, String manifestContent) throws IOException {
    var manifest = tmpDir.resolve("Cargo.toml");
    Files.writeString(manifest, manifestContent);

    SensorContextTester sct = Mockito.spy(SensorContextTester.create(tmpDir).setRuntime(SONAR_RUNTIME));

    Telemetry.reportManifestInfo(sct, manifest);

    if (expected != null) {
      Mockito.verify(sct).addTelemetryProperty("rust.language.edition", expected);
    } else {
      Mockito.verify(sct, Mockito.never()).addTelemetryProperty(Mockito.anyString(), Mockito.anyString());
    }
  }

  @Test
  void report_manifest_info_io_error() {
    var manifest = tmpDir.resolve("noSuchCargo.toml");
    SensorContextTester sct = Mockito.spy(SensorContextTester.create(tmpDir).setRuntime(SONAR_RUNTIME));

    Telemetry.reportManifestInfo(sct, manifest);

    Mockito.verify(sct, Mockito.never()).addTelemetryProperty(Mockito.anyString(), Mockito.anyString());
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
    SensorContextTester sct = Mockito.spy(SensorContextTester.create(tmpDir).setRuntime(SONAR_RUNTIME));
    Telemetry.reportClippyVersion(sct, versionString);

    if (expected != null) {
      Mockito.verify(sct).addTelemetryProperty("rust.clippy.version", expected);
    } else {
      Mockito.verify(sct, Mockito.never()).addTelemetryProperty(Mockito.anyString(), Mockito.anyString());
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


}
