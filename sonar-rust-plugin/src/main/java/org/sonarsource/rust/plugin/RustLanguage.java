/*
 * SonarQube Rust Plugin
 * Copyright (C) 2025 SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
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
