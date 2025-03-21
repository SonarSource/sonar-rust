/*
 * SonarQube Rust Plugin
 * Copyright (C) 2025 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the Sonar Source-Available License Version 1, as published by SonarSource SA.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */
package com.sonarsource.rust.cargo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonarsource.analyzer.commons.FileProvider;

public class CargoManifestProvider {

  private static final Logger LOG = LoggerFactory.getLogger(CargoManifestProvider.class);
  private static final String CARGO_MANIFEST_NAME = "Cargo.toml";

  public static final String CARGO_MANIFEST_PATHS = "sonar.rust.cargo.manifestPaths";

  /**
   * A strategy interface for providing manifest paths.
   */
  private interface ManifestProviderStrategy {
    List<File> getManifests(SensorContext context);
  }

  /**
   * A strategy implementation for providing manifest paths based on a property.
   */
  private static class PropertyBasedProvider implements ManifestProviderStrategy {
    @Override
    public List<File> getManifests(SensorContext context) {
      LOG.debug("Looking for Cargo manifest paths using {}", CARGO_MANIFEST_PATHS);

      var manifestPatterns = context.config().getStringArray(CARGO_MANIFEST_PATHS);
      if (manifestPatterns.length == 0) {
        LOG.debug("No Cargo manifest paths were provided");
        return List.of();
      }

      var manifests = new ArrayList<File>();
      for (var manifestPattern : manifestPatterns) {
        LOG.debug("Attempting to resolve Cargo manifest path: {}", manifestPattern);

        // Case 1: If the path denotes a file, we use it as is.
        var baseDir = context.fileSystem().baseDir();
        var manifest = new File(manifestPattern);
        if (!manifest.isAbsolute()) {
          manifest = new File(baseDir, manifestPattern);
        }
        if (manifest.isFile()) {
          manifests.add(manifest);
          continue;
        } else {
          LOG.debug("Cargo manifest path does not exist: {}", manifest);
        }

        LOG.debug("Attempting to resolve Cargo manifest pattern: {}", manifestPattern);

        // Case 2: If the path denotes a pattern, we resolve all matching files.
        var provider = new FileProvider(baseDir, manifestPattern);
        var matchingFiles = provider.getMatchingFiles();
        if (matchingFiles.isEmpty()) {
          LOG.debug("No Cargo manifest matched the pattern: {}", manifestPattern);
          continue;
        } else {
          LOG.debug("Found Cargo manifests: {}", matchingFiles);
        }

        manifests.addAll(matchingFiles);
      }

      return manifests;
    }
  }

  /**
   * A strategy implementation for providing the Cargo manifest file located at the root of the project.
   */
  private static class ProjectRootProvider implements ManifestProviderStrategy {
    @Override
    public List<File> getManifests(SensorContext context) {
      LOG.debug("Looking for Cargo manifest at the root of the project");

      var baseDir = context.fileSystem().baseDir();
      var manifest = baseDir.toPath().resolve(CARGO_MANIFEST_NAME).toFile();
      if (manifest.exists()) {
        LOG.debug("Found Cargo manifest: {}", manifest);
        return List.of(manifest);
      }
      return List.of();
    }
  }

  private CargoManifestProvider() {
    // utility class
  }

  public static List<File> getManifests(SensorContext context) {
    LOG.debug("Looking for Cargo manifests");

    var strategies = List.of(
      new PropertyBasedProvider(),
      new ProjectRootProvider()
    );
    for (var strategy : strategies) {
      var manifests = strategy.getManifests(context);
      if (!manifests.isEmpty()) {
        LOG.debug("Found Cargo manifests: {}", manifests);
        return manifests;
      }
    }

    LOG.debug("No Cargo manifest found");

    return List.of();
  }
}
