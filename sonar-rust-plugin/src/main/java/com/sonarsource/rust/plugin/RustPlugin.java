/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import org.sonar.api.Plugin;
import org.sonar.api.config.PropertyDefinition;
import org.sonar.api.resources.Qualifiers;

public class RustPlugin implements Plugin {
  @Override
  public void define(Context context) {
    context.addExtensions(
      RustLanguage.class,
      RustProfile.class
    );
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
  }
}
