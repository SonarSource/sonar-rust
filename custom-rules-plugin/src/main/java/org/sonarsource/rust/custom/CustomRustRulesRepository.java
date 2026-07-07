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

import java.util.Collection;
import java.util.List;
import org.sonar.plugins.rust.api.RustRulesRepository;

/**
 * Contributes the fixture plugin's rules to the built-in "Sonar way" profile through the
 * {@link RustRulesRepository} extension point exposed by the base sonar-rust plugin.
 */
public class CustomRustRulesRepository implements RustRulesRepository {

  @Override
  public Collection<String> ruleKeys() {
    return List.of(
      CustomRulesDefinition.REPOSITORY + ":" + CustomRulesDefinition.OVERRIDING_RULE,
      CustomRulesDefinition.REPOSITORY + ":" + CustomRulesDefinition.NEW_RULE);
  }
}
