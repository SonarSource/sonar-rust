/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import com.sonarsource.rust.clippy.ClippyRulesDefinition;
import com.sonarsource.rust.clippy.ClippySensor;
import org.sonar.api.Plugin;
import org.sonar.api.config.PropertyDefinition;
import org.sonar.api.resources.Qualifiers;

public class RustPlugin implements Plugin {

  @Override
  public void define(Context context) {
    context.addExtensions(
      RustLanguage.class,
      RustProfile.class,
      RustSensor.class,
      AnalyzerFactory.class,
      ClippyRulesDefinition.class,
      ClippySensor.class
    );

    // Rust file suffixes
    context.addExtension(
      PropertyDefinition
        .builder(RustLanguage.FILE_SUFFIXES_PROPERTY)
        .category("Rust")
        .name("Rust file suffixes")
        .description("List of suffixes of Rust files to index and consider for analyses.")
        .onQualifiers(Qualifiers.PROJECT)
        .multiValues(true)
        .defaultValue(RustLanguage.FILE_SUFFIXES_DEFAULT_VALUE)
        .build());

    // Clippy report paths
    context.addExtension(
      PropertyDefinition
        .builder(ClippySensor.CLIPPY_REPORT_PATHS)
        .category("Rust")
        .name("Clippy report paths")
        .description("Comma-delimited list of paths to Clippy reports generated with the command "
          + "<code>cargo clippy --message-format=json</code>.")
        .onQualifiers(Qualifiers.PROJECT)
        .multiValues(true)
        .build());
  }
}
