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
package org.sonarsource.rust.clippy;

import static org.assertj.core.api.Assertions.assertThat;

import org.sonarsource.rust.plugin.RustLanguage;
import org.junit.jupiter.api.Test;
import org.sonar.api.server.rule.RulesDefinition;

class ClippyRulesDefinitionTest {

  @Test
  void test() {
    var context = new RulesDefinition.Context();
    var definition = new ClippyRulesDefinition();
    definition.define(context);

    assertThat(context.repositories()).hasSize(1);

    var repository = context.repository("external_" + ClippyRulesDefinition.LINTER_KEY);
    assertThat(repository).isNotNull();
    assertThat(repository.name()).isEqualTo(ClippyRulesDefinition.LINTER_NAME);
    assertThat(repository.language()).isEqualTo(RustLanguage.KEY);
    assertThat(repository.isExternal()).isTrue();

    var rules = repository.rules();
    assertThat(rules).hasSize(770);

    var rule = repository.rule("unit_cmp");
    assertThat(rule).isNotNull();
    assertThat(rule.name()).isEqualTo("unit_cmp");
    assertThat(rule.htmlDescription()).contains("<p>Clippy lint <code>unit_cmp</code>");
  }
}
