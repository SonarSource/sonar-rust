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
package org.sonarsource.rust.plugin;

import org.sonarsource.rust.cargo.CargoManifestProvider;
import org.sonarsource.rust.clippy.ClippyReportSensor;
import org.sonarsource.rust.clippy.ClippyRulesDefinition;
import org.sonarsource.rust.clippy.ClippySensor;
import org.sonarsource.rust.coverage.CoberturaSensor;
import org.sonarsource.rust.coverage.LcovSensor;
import org.sonar.api.Plugin;
import org.sonar.api.config.PropertyDefinition;
import org.sonar.api.config.PropertyDefinition.ConfigScope;

public class RustPlugin implements Plugin {

  public static final String FAIL_FAST_PROPERTY = "sonar.internal.analysis.rust.failFast";

  @Override
  public void define(Context context) {
    context.addExtensions(
      // keep sorted alphabetically
      AnalysisWarningsWrapper.class,
      AnalyzerFactory.class,
      ClippyRulesDefinition.class,
      ClippyReportSensor.class,
      ClippySensor.class,
      CoberturaSensor.class,
      LcovSensor.class,
      RustLanguage.class,
      RustProfile.class,
      RustRulesDefinition.class,
      RustSensor.class
    );

    ////////////////////////// ANALYSIS SCOPE //////////////////////////

    // Rust file suffixes
    context.addExtension(
      PropertyDefinition
        .builder(RustLanguage.FILE_SUFFIXES_PROPERTY)
        .category("Rust")
        .subCategory("Analysis Scope")
        .name("Rust file suffixes")
        .description("List of suffixes of Rust files to index and consider for analyses.")
        .onConfigScopes(ConfigScope.PROJECT)
        .multiValues(true)
        .defaultValue(RustLanguage.FILE_SUFFIXES_DEFAULT_VALUE)
        .build());

    // Cargo manifest paths
    context.addExtension(
      PropertyDefinition
        .builder(CargoManifestProvider.CARGO_MANIFEST_PATHS)
        .category("Rust")
        .subCategory("Analysis Scope")
        .name("Cargo manifest paths")
        .description("Comma-delimited list of paths to Cargo.toml files. The root Cargo.toml, if any, is considered by default.")
        .onConfigScopes(ConfigScope.PROJECT)
        .multiValues(true)
        .build());

    ////////////////////////// CLIPPY //////////////////////////

    // Clippy report paths
    context.addExtension(
      PropertyDefinition
        .builder(ClippyReportSensor.CLIPPY_REPORT_PATHS)
        .category("Rust")
        .subCategory("Clippy")
        .name("Clippy report paths")
        .description("Comma-delimited list of paths to Clippy reports generated with the command "
          + "<code>cargo clippy --message-format=json</code>.")
        .onConfigScopes(ConfigScope.PROJECT)
        .multiValues(true)
        .build());

    // Clippy report paths
    context.addExtension(
      PropertyDefinition
        .builder(ClippySensor.CLIPPY_ANALYSIS_ENABLED)
        .category("Rust")
        .subCategory("Clippy")
        .name("Execute Clippy analysis")
        .description("Whether to execute Clippy analysis.")
        .onConfigScopes(ConfigScope.PROJECT)
        .defaultValue("true")
        .build());

    ////////////////////////// COVERAGE //////////////////////////

    // LCOV report paths
    context.addExtension(
      PropertyDefinition
        .builder(LcovSensor.COVERAGE_REPORT_PATHS)
        .category("Rust")
        .subCategory("Coverage")
        .name("LCOV report paths")
        .description("Comma-delimited list of paths to LCOV reports.")
        .onConfigScopes(ConfigScope.PROJECT)
        .multiValues(true)
        .build());

    // Cobertura report paths
    context.addExtension(
      PropertyDefinition
        .builder(CoberturaSensor.COBERTURA_REPORT_PATHS)
        .category("Rust")
        .subCategory("Coverage")
        .name("Cobertura report paths")
        .description("Comma-delimited list of paths to Cobertura reports.")
        .onConfigScopes(ConfigScope.PROJECT)
        .multiValues(true)
        .build());
  }
}
