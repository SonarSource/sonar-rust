/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.sonar.api.SonarEdition;
import org.sonar.api.SonarQubeSide;
import org.sonar.api.internal.SonarRuntimeImpl;
import org.sonar.api.server.profile.BuiltInQualityProfilesDefinition;
import org.sonar.api.server.rule.RulesDefinition;
import org.sonar.api.utils.Version;

class RustRulesDefinitionTest {

  @Test
  void testClippyRules() {
    var rules = RustRulesDefinition.CLIPPY_RULES;
    assertThat(rules.keySet()).hasSize(new HashSet<>(rules.values()).size());
    assertThat(rules.keySet()).hasSize(46);
  }

  @Test
  void testSonarRules() {
    var rules = RustRulesDefinition.SONAR_RULES;
    assertThat(rules).hasSize(1);
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
    assertThat(profile.rules()).hasSize(RustRulesDefinition.CLIPPY_RULES.size() + RustRulesDefinition.SONAR_RULES.size());

    for (var rule : profile.rules()) {
      assertThat(repository.rule(rule.ruleKey())).isNotNull();
    }
  }
}
