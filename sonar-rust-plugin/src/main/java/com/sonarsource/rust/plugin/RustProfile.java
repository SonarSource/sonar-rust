/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import org.sonar.api.server.profile.BuiltInQualityProfilesDefinition;

public class RustProfile implements BuiltInQualityProfilesDefinition {

  @Override
  public void define(Context context) {
    var builtInQualityProfile = context.createBuiltInQualityProfile("Sonar way", RustLanguage.KEY);
    builtInQualityProfile.done();
  }
}
