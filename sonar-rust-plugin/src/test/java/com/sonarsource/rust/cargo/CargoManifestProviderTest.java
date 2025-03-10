/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.cargo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonar.api.batch.sensor.internal.SensorContextTester;

class CargoManifestProviderTest {

  @TempDir
  Path baseDir;

  @Test
  void testProjectRootManifest() throws IOException {
    var manifest = Files.createFile(baseDir.resolve("Cargo.toml")).toFile();
    var context = SensorContextTester.create(baseDir);

    var manifests = CargoManifestProvider.getManifests(context);

    assertThat(manifests).containsExactly(manifest);
  }

  @Test
  void testPropertyPathManifest() throws IOException {
    var subDir = Files.createDirectories(baseDir.resolve("subdir"));
    var manifest = Files.createFile(subDir.resolve("Cargo.toml")).toFile();
    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty(CargoManifestProvider.CARGO_MANIFEST_PATHS, manifest.getAbsolutePath());

    var manifests = CargoManifestProvider.getManifests(context);

    assertThat(manifests).containsExactly(manifest);
  }

  @Test
  void testPropertyPatternManifestMatch() throws IOException {
    var subDir = Files.createDirectories(baseDir.resolve("subdir"));
    var manifest = Files.createFile(subDir.resolve("Cargo.toml")).toFile();
    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty(CargoManifestProvider.CARGO_MANIFEST_PATHS, "**/Cargo.toml");

    var manifests = CargoManifestProvider.getManifests(context);

    assertThat(manifests).containsExactly(manifest);
  }

  @Test
  void testPropertyPatternManifestNoMatch() {
    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty(CargoManifestProvider.CARGO_MANIFEST_PATHS, "**/Cargo.toml");

    var manifests = CargoManifestProvider.getManifests(context);

    assertThat(manifests).isEmpty();
  }

  @Test
  void testNoManifestFound() {
    var context = SensorContextTester.create(baseDir);
    var manifests = CargoManifestProvider.getManifests(context);

    assertThat(manifests).isEmpty();
  }
}
