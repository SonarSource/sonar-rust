/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.sonar.api.config.internal.MapSettings;

class RustLanguageTest {

  @Test
  void test() {
    var language = new RustLanguage(new MapSettings().setProperty("sonar.rust.file.suffixes", ".foo, .bar").asConfig());
    assertEquals("rust", language.getKey());
    assertEquals("Rust", language.getName());
    assertArrayEquals(new String[] {".foo", ".bar"}, language.getFileSuffixes());
  }

}
