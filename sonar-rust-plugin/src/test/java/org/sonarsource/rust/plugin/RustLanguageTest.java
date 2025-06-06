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
