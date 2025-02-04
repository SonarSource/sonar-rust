/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.sonar.api.Plugin;
import org.sonar.api.SonarEdition;
import org.sonar.api.SonarQubeSide;
import org.sonar.api.internal.SonarRuntimeImpl;
import org.sonar.api.utils.Version;

class RustPluginTest {

  @Test
  void test() {
    var context = new Plugin.Context(SonarRuntimeImpl.forSonarQube(
        Version.create(10, 9),
        SonarQubeSide.SCANNER,
        SonarEdition.DEVELOPER));
    new RustPlugin().define(context);
    assertEquals(3, context.getExtensions().size());
  }

}
