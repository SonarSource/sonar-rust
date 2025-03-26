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
package com.sonarsource.rust.plugin;

import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.sonar.api.SonarEdition;
import org.sonar.api.SonarQubeSide;
import org.sonar.api.internal.SonarRuntimeImpl;
import org.sonar.api.server.profile.BuiltInQualityProfilesDefinition;
import org.sonar.api.server.rule.RulesDefinition;
import org.sonar.api.utils.Version;

import static org.assertj.core.api.Assertions.assertThat;

class RustRulesDefinitionTest {

  @Test
  void testClippyRules() {
    var rules = RustRulesDefinition.CLIPPY_RULES;
    assertThat(rules.keySet()).hasSize(new HashSet<>(rules.values()).size());
    assertThat(rules.keySet()).hasSize(72);
    assertThat(rules.keySet()).allSatisfy(ruleKey -> assertThat(ruleKey).startsWith("clippy::"));
  }

  @Test
  void testSonarRules() {
    var rules = RustRulesDefinition.SONAR_RULES;
    assertThat(rules).hasSize(2);
  }

  @Test
  void testRepository() {
    var runtime = SonarRuntimeImpl.forSonarQube(Version.create(9, 8), SonarQubeSide.SERVER, SonarEdition.DEVELOPER);
    var definition = new RustRulesDefinition(runtime);
    var definitionContext = new RulesDefinition.Context();
    definition.define(definitionContext);

    var repository = definitionContext.repository(RustLanguage.KEY);
    assertThat(repository).isNotNull();
    assertThat(repository.name()).isEqualTo("Sonar");
    assertThat(repository.language()).isEqualTo(RustLanguage.KEY);
    assertThat(repository.rules()).hasSize(RustRulesDefinition.CLIPPY_RULES.size() + RustRulesDefinition.SONAR_RULES.size());
  }

  @Test
  void testSonarWay() {
    var runtime = SonarRuntimeImpl.forSonarQube(Version.create(9, 8), SonarQubeSide.SERVER, SonarEdition.DEVELOPER);
    var definition = new RustRulesDefinition(runtime);
    var definitionContext = new RulesDefinition.Context();
    definition.define(definitionContext);

    var repository = definitionContext.repository(RustLanguage.KEY);
    var profileContext = new BuiltInQualityProfilesDefinition.Context();
    new RustProfile().define(profileContext);

    var profile = profileContext.profile(RustLanguage.KEY, "Sonar way");
    assertThat(profile.rules()).isNotEmpty();

    for (var rule : profile.rules()) {
      assertThat(repository.rule(rule.ruleKey())).isNotNull();
    }
  }

  @Test
  void parameters() {
    // Make sure that parameters are defined only for known Sonar rules
    var runtime = SonarRuntimeImpl.forSonarQube(Version.create(9, 8), SonarQubeSide.SERVER, SonarEdition.DEVELOPER);
    var definition = new RustRulesDefinition(runtime);
    var definitionContext = new RulesDefinition.Context();
    definition.define(definitionContext);

    var repository = definitionContext.repository(RustLanguage.KEY);

    assertThat(RustRulesDefinition.parameters()).extracting(RustRulesDefinition.RuleParameter::ruleKey)
      .allSatisfy(ruleKey -> assertThat(RustRulesDefinition.SONAR_RULES).contains(ruleKey))
      .allSatisfy(ruleKey -> assertThat(repository.rule(ruleKey)).isNotNull());
  }
}
