/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import com.sonarsource.rust.clippy.ClippyReportSensor;
import com.sonarsource.rust.clippy.ClippyRulesDefinition;
import com.sonarsource.rust.clippy.ClippySensor;
import com.sonarsource.rust.coverage.CoverageSensor;
import org.sonar.api.Plugin;
import org.sonar.api.config.PropertyDefinition;
import org.sonar.api.config.PropertyDefinition.ConfigScope;

public class RustPlugin implements Plugin {

  @Override
  public void define(Context context) {
    context.addExtensions(
      // keep sorted alphabetically
      AnalyzerFactory.class,
      ClippyRulesDefinition.class,
      ClippyReportSensor.class,
      ClippySensor.class,
      CoverageSensor.class,
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
        .builder(ClippySensor.CLIPPY_SENSOR_ENABLED)
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
        .builder(CoverageSensor.COVERAGE_REPORT_PATHS)
        .category("Rust")
        .subCategory("Coverage")
        .name("LCOV report paths")
        .description("Comma-delimited list of paths to LCOV reports.")
        .onConfigScopes(ConfigScope.PROJECT)
        .multiValues(true)
        .build());
  }
}
