/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import org.sonar.api.config.Configuration;
import org.sonar.api.resources.Language;

public class RustLanguage implements Language {

  public static final String KEY = "rust";
  public static final String FILE_SUFFIXES_PROPERTY = "sonar.rust.file.suffixes";
  public static final String FILE_SUFFIXES_DEFAULT_VALUE = ".rs";
  private final Configuration configuration;

  public RustLanguage(Configuration configuration) {
    this.configuration = configuration;
  }

  @Override
  public String getKey() {
    return KEY;
  }

  @Override
  public String getName() {
    return "Rust";
  }

  @Override
  public String[] getFileSuffixes() {
    return configuration.getStringArray(FILE_SUFFIXES_PROPERTY);
  }
}
