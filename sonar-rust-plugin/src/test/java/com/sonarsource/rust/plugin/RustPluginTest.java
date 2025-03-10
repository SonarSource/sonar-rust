/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import org.junit.jupiter.api.Test;
import org.sonar.api.Plugin;
import org.sonar.api.SonarEdition;
import org.sonar.api.SonarQubeSide;
import org.sonar.api.internal.SonarRuntimeImpl;
import org.sonar.api.utils.Version;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RustPluginTest {

  @Test
  void test() {
    var context = new Plugin.Context(SonarRuntimeImpl.forSonarQube(
        Version.create(10, 9),
        SonarQubeSide.SCANNER,
        SonarEdition.DEVELOPER));
    new RustPlugin().define(context);
    assertEquals(16, context.getExtensions().size());
  }
}
