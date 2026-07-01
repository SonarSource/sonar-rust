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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.sonar.api.server.profile.BuiltInQualityProfilesDefinition;
import org.sonar.plugins.rust.api.RustRulesRepository;
import org.sonarsource.analyzer.commons.BuiltInQualityProfileJsonLoader;

public class RustProfile implements BuiltInQualityProfilesDefinition {

  private static final String SONAR_WAY_PATH = "/org/sonar/l10n/rust/rules/rust/Sonar_way_profile.json";

  private final RustRulesRepository[] repositories;

  public RustProfile() {
    this(new RustRulesRepository[0]);
  }

  public RustProfile(RustRulesRepository[] repositories) {
    this.repositories = repositories;
  }

  @Override
  public void define(Context context) {
    var profile = context.createBuiltInQualityProfile("Sonar way", RustLanguage.KEY);

    // Rule ids reimplemented by a contributing plugin supersede the base "rust" rule with the same
    // id: the contributed rule is activated instead of the base one below.
    Set<String> overriddenRuleIds = Arrays.stream(repositories)
      .flatMap(repository -> repository.ruleKeys().stream())
      .map(RustProfile::ruleId)
      .collect(Collectors.toSet());

    for (String baseRuleKey : BuiltInQualityProfileJsonLoader.loadActiveKeysFromJsonProfile(SONAR_WAY_PATH)) {
      if (!overriddenRuleIds.contains(baseRuleKey)) {
        profile.activateRule(RustLanguage.KEY, baseRuleKey);
      }
    }

    Set<String> activated = new HashSet<>();
    for (RustRulesRepository repository : repositories) {
      for (String ruleKey : repository.ruleKeys()) {
        // Guard against the same rule being contributed by more than one repository.
        if (activated.add(ruleKey)) {
          profile.activateRule(repositoryKey(ruleKey), ruleId(ruleKey));
        }
      }
    }

    profile.done();
  }

  private static String repositoryKey(String ruleKey) {
    return ruleKey.substring(0, ruleKey.indexOf(':'));
  }

  private static String ruleId(String ruleKey) {
    return ruleKey.substring(ruleKey.indexOf(':') + 1);
  }
}
