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
package org.sonarsource.rust.plugin;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.sonar.api.server.profile.BuiltInQualityProfilesDefinition;
import org.sonar.api.server.profile.BuiltInQualityProfilesDefinition.BuiltInActiveRule;
import org.sonar.plugins.rust.api.RustRulesRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class RustProfileTest {

  @Test
  void shouldActivateBaseRulesWhenNoContributor() {
    var profile = defineProfile(new RustProfile());

    assertThat(profile.rules())
      .extracting(BuiltInActiveRule::repoKey)
      .containsOnly("rust");
    assertThat(profile.rules())
      .extracting(BuiltInActiveRule::ruleKey)
      .contains("S3776", "S2589");
  }

  @Test
  void shouldActivateContributedRules() {
    var profile = defineProfile(new RustProfile(new RustRulesRepository[] {
      () -> List.of("rustenterprise:S8794", "rustenterprise:S8861")
    }));

    assertThat(profile.rules())
      .extracting(BuiltInActiveRule::repoKey, BuiltInActiveRule::ruleKey)
      .contains(
        tuple("rustenterprise", "S8794"),
        tuple("rustenterprise", "S8861"));
  }

  @Test
  void shouldPreferContributedRuleOverBaseRuleWithSameId() {
    // S2589 is present in the base Sonar way profile.
    var profile = defineProfile(new RustProfile(new RustRulesRepository[] {
      () -> List.of("rustenterprise:S2589")
    }));

    assertThat(profile.rules())
      .extracting(BuiltInActiveRule::repoKey, BuiltInActiveRule::ruleKey)
      .contains(tuple("rustenterprise", "S2589"))
      .doesNotContain(tuple("rust", "S2589"));
  }

  @Test
  void shouldActivateContributedRuleOnlyOnceWhenProvidedTwice() {
    var profile = defineProfile(new RustProfile(new RustRulesRepository[] {
      () -> List.of("rustenterprise:S8794"),
      () -> List.of("rustenterprise:S8794")
    }));

    assertThat(profile.rules())
      .filteredOn(rule -> "rustenterprise".equals(rule.repoKey()) && "S8794".equals(rule.ruleKey()))
      .hasSize(1);
  }

  private static BuiltInQualityProfilesDefinition.BuiltInQualityProfile defineProfile(RustProfile rustProfile) {
    var context = new BuiltInQualityProfilesDefinition.Context();
    rustProfile.define(context);
    return context.profile(RustLanguage.KEY, "Sonar way");
  }
}
