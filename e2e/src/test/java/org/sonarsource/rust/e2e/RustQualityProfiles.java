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
package org.sonarsource.rust.e2e;

import java.util.Set;
import java.util.stream.Collectors;
import org.sonarqube.ws.Qualityprofiles;
import org.sonarqube.ws.client.WsClient;
import org.sonarqube.ws.client.qualityprofiles.SearchRequest;
import org.sonarqube.ws.client.qualityprofiles.SetDefaultRequest;

/**
 * Since SonarQube 2026.6 (SONAR-32511), every language ships three built-in profiles ("Sonar way
 * core", "Sonar way extended" and "Sonar way comprehensive") instead of a single "Sonar way", and
 * "Sonar way core" — a reduced rule set — is the new default. The e2e tests were written against
 * the full "Sonar way" rule set, so on tiered SonarQube versions we restore "Sonar way
 * comprehensive" (the superset profile) as the default for the "rust" language. Older SonarQube
 * versions only ship "Sonar way" and are left untouched.
 */
final class RustQualityProfiles {

  private static final String LANGUAGE = "rust";
  private static final String CORE_PROFILE = "Sonar way core";
  private static final String COMPREHENSIVE_PROFILE = "Sonar way comprehensive";

  private RustQualityProfiles() {
  }

  static void restoreComprehensiveDefaultProfile(WsClient client) {
    Set<Qualityprofiles.SearchWsResponse.QualityProfile> builtInProfiles = client.qualityprofiles()
      .search(new SearchRequest().setLanguage(LANGUAGE))
      .getProfilesList().stream()
      .filter(Qualityprofiles.SearchWsResponse.QualityProfile::getIsBuiltIn)
      .collect(Collectors.toSet());

    boolean isTiered = builtInProfiles.stream().anyMatch(p -> CORE_PROFILE.equals(p.getName()));
    if (!isTiered) {
      return;
    }

    boolean hasComprehensive = builtInProfiles.stream().anyMatch(p -> COMPREHENSIVE_PROFILE.equals(p.getName()));
    if (!hasComprehensive) {
      throw new IllegalStateException("Language '" + LANGUAGE + "' has built-in profile '" + CORE_PROFILE
        + "' but no '" + COMPREHENSIVE_PROFILE + "'");
    }

    client.qualityprofiles().setDefault(new SetDefaultRequest()
      .setLanguage(LANGUAGE)
      .setQualityProfile(COMPREHENSIVE_PROFILE));
  }
}
