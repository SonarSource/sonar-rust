/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import org.sonar.api.server.profile.BuiltInQualityProfilesDefinition;
import org.sonarsource.analyzer.commons.BuiltInQualityProfileJsonLoader;

public class RustProfile implements BuiltInQualityProfilesDefinition {

  private static final String SONAR_WAY_PATH = "/org/sonar/l10n/rust/rules/rust/Sonar_way_profile.json";

  @Override
  public void define(Context context) {
    var builtInQualityProfile = context.createBuiltInQualityProfile("Sonar way", RustLanguage.KEY);
    BuiltInQualityProfileJsonLoader.load(builtInQualityProfile, RustLanguage.KEY, SONAR_WAY_PATH);
    builtInQualityProfile.done();
  }
}
