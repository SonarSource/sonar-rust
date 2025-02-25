/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.e2e.helpers;

import com.sonar.orchestrator.junit5.OrchestratorExtension;
import com.sonar.orchestrator.locator.FileLocation;
import com.sonar.orchestrator.locator.Location;
import com.sonar.orchestrator.locator.MavenLocation;
import java.io.File;

public class OrchestratorHelper {

  // The sonar-rust-plugin jar is retrieved from different locations depending on the environment.
  // When running tests locally, the jar is retrieved from the local build output directory.
  // When running tests on CI, the jar is retrieved from JFrog, where it was published during the CI build process.
  private static final Location pluginLocation;

  static {
    var version = System.getProperty("pluginVersion");
    if (version == null || version.equals("")) {
      pluginLocation = FileLocation.byWildcardMavenFilename(new File("../sonar-rust-plugin/build/libs"), "sonar-rust-plugin-*.jar");
    } else {
      pluginLocation = MavenLocation.of("com.sonarsource.rust", "sonar-rust-plugin", version);
    }
  }

  public static OrchestratorExtension createOrchestrator() {
    return OrchestratorExtension.builderEnv()
      .useDefaultAdminCredentialsForBuilds(true)
      .setSonarVersion(System.getProperty("sonar.runtimeVersion", "LATEST_RELEASE"))
      .addPlugin(pluginLocation)
      .build();
  }
}
