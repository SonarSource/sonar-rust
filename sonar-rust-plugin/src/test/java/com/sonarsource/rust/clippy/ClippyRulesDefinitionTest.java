/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.clippy;

import static org.assertj.core.api.Assertions.assertThat;

import com.sonarsource.rust.plugin.RustLanguage;
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
