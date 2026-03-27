/*
 * SonarQube Rust Plugin
 * Copyright (C) SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
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

import static org.junit.jupiter.api.extension.ExtensionContext.Namespace.GLOBAL;

import com.sonar.orchestrator.build.SonarScanner;
import com.sonar.orchestrator.container.Edition;
import com.sonar.orchestrator.junit5.OrchestratorExtension;
import com.sonar.orchestrator.locator.FileLocation;
import com.sonar.orchestrator.locator.Location;
import com.sonar.orchestrator.locator.MavenLocation;
import java.io.File;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public class OrchestratorHelper implements BeforeAllCallback,  ExtensionContext.Store.CloseableResource {

  private static volatile boolean started;
  private static final OrchestratorExtension orchestrator = createOrchestrator();

  public static OrchestratorExtension createOrchestrator() {
    // The sonar-rust-plugin jar is retrieved from different locations depending on the environment.
    // When running tests locally, the jar is retrieved from the local build output directory.
    // When running tests on CI, the jar is retrieved from JFrog, where it was published during the CI build process.
    Location pluginLocation;
    var version = System.getProperty("pluginVersion");
    if (version == null || version.isEmpty()) {
      pluginLocation = FileLocation.byWildcardMavenFilename(new File("../sonar-rust-plugin/build/libs"), "sonar-rust-plugin-*.jar");
    } else {
      pluginLocation = MavenLocation.of("org.sonarsource.rust", "sonar-rust-plugin", version);
    }
    return OrchestratorExtension.builderEnv()
      .setEdition(Edition.ENTERPRISE_LW)
      .activateLicense()
      .useDefaultAdminCredentialsForBuilds(true)
      .setSonarVersion(System.getProperty("sonar.runtimeVersion", "LATEST_RELEASE"))
      .addPlugin(pluginLocation)
      .build();
  }

  @Override
  public void beforeAll(ExtensionContext context) throws Exception {
    if (!started) {
      started = true;
      // this will register "this.close()" method to be called when GLOBAL context is shutdown
      context.getRoot().getStore(GLOBAL).put(OrchestratorHelper.class, this);
      orchestrator.start();
    }
  }

  @Override
  public void close() throws Throwable {
    orchestrator.stop();
  }

  static OrchestratorExtension orchestrator() {
    return orchestrator;
  }

  static SonarScanner createSonarScanner() {
    return SonarScanner.create()
      .setProperty("sonar.scanner.skipJreProvisioning", "true");
  }
}
