/*
 * SonarQube Rust Plugin
 * Copyright (C) SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * You can redistribute and/or modify this program under the terms of
 * the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */
package org.sonarsource.rust.custom;

import org.sonar.api.Plugin;

/**
 * Entry point of a minimal test-fixture plugin used by the e2e module to exercise the
 * {@link org.sonar.plugins.rust.api.RustRulesRepository} extension point exposed by sonar-rust,
 * independently of any other plugin.
 */
public class CustomRulesPlugin implements Plugin {

  @Override
  public void define(Context context) {
    context.addExtensions(
      CustomRulesDefinition.class,
      CustomRustRulesRepository.class);
  }
}
