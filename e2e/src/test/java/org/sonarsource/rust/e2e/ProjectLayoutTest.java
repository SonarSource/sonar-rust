/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package org.sonarsource.rust.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.sonar.orchestrator.container.Server;
import com.sonar.orchestrator.junit5.OrchestratorExtension;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sonarqube.ws.Issues;
import org.sonarqube.ws.client.HttpConnector;
import org.sonarqube.ws.client.WsClientFactories;
import org.sonarqube.ws.client.issues.SearchRequest;

@ExtendWith(OrchestratorHelper.class)
class ProjectLayoutTest {

  private static final OrchestratorExtension orchestrator = OrchestratorHelper.orchestrator();

  @Test
  void testPackageLayout() throws Exception {
    /**
     * Tested layout:
     * - A single Rust package with a root Cargo.toml file
     * 
     * package
     *  └ src
     *     └ main.rs
     *  └ Cargo.toml
     */

    var projectKey = "package-layout";
    var projectName = "Package Layout";
    var projectDir = Paths.get(getClass().getClassLoader().getResource("projects/layouts/package").toURI()).toFile();

    var scanner = OrchestratorHelper.createSonarScanner()
      .setProjectKey(projectKey)
      .setProjectName(projectName)
      .setProjectDir(projectDir)
      .setProperty("sonar.internal.analysis.rust.failFast", "true")
      .setSourceDirs(".");

    var wsClient = WsClientFactories.getDefault()
      .newClient(HttpConnector.newBuilder()
        .url(orchestrator.getServer().getUrl())
        .credentials(Server.ADMIN_LOGIN, Server.ADMIN_PASSWORD)
        .build());
    
    orchestrator.executeBuild(scanner);

    var request = new SearchRequest().setComponentKeys(List.of(projectKey));
    var issues = wsClient.issues().search(request).getIssuesList();

    assertThat(issues)
      .extracting(Issues.Issue::getLine, Issues.Issue::getComponent, Issues.Issue::getRule)
      .containsExactlyInAnyOrder(
        tuple(2, "package-layout:src/main.rs", "rust:S2198")
      );
  }

  @Test
  void testHybridLayout() throws Exception {
    /**
     * Tested layout:
     *  - A hybrid project with a root Node.js package and a Rust component
     * 
     * hybrid
     *  └ rust-crate
     *     └ src
     *        └ main.rs
     *     └ Cargo.toml
     *  └ src
     *     └ index.js
     *  └ package.json
     */

    var projectKey = "hybrid-layout";
    var projectName = "Hybrid Layout";
    var projectDir = Paths.get(getClass().getClassLoader().getResource("projects/layouts/hybrid").toURI()).toFile();

    var scanner = OrchestratorHelper.createSonarScanner()
      .setProjectKey(projectKey)
      .setProjectName(projectName)
      .setProjectDir(projectDir)
      .setProperty("sonar.internal.analysis.rust.failFast", "true")
      .setSourceDirs(".")
      .setProperty("sonar.rust.cargo.manifestPaths", "rust-crate/Cargo.toml");

    var wsClient = WsClientFactories.getDefault()
      .newClient(HttpConnector.newBuilder()
        .url(orchestrator.getServer().getUrl())
        .credentials(Server.ADMIN_LOGIN, Server.ADMIN_PASSWORD)
        .build());
    
    orchestrator.executeBuild(scanner);

    var request = new SearchRequest().setComponentKeys(List.of(projectKey));
    var issues = wsClient.issues().search(request).getIssuesList();

    assertThat(issues)
      .extracting(Issues.Issue::getLine, Issues.Issue::getComponent, Issues.Issue::getRule)
      .containsExactlyInAnyOrder(
        tuple(2, "hybrid-layout:rust-crate/src/main.rs", "rust:S2198")
      );
  }

  @Test
  void testMonorepoLayout() throws Exception {
    /**
     * Tested layout:
     *  - A monorepo with multiple Rust crates without a root Cargo.toml file
     * 
     * monorepo
     *  └ crate1
     *     └ src
     *        └ main.rs
     *     └ Cargo.toml
     *  └ crate2
     *     └ src
     *        └ main.rs
     *     └ Cargo.toml
     *  <no root Cargo.toml>
     */

    var projectKey = "monorepo-layout";
    var projectName = "Monorepo Layout";
    var projectDir = Paths.get(getClass().getClassLoader().getResource("projects/layouts/monorepo").toURI()).toFile();

    var scanner = OrchestratorHelper.createSonarScanner()
      .setProjectKey(projectKey)
      .setProjectName(projectName)
      .setProjectDir(projectDir)
      .setProperty("sonar.internal.analysis.rust.failFast", "true")
      .setSourceDirs(".")
      .setProperty("sonar.rust.cargo.manifestPaths", "**/Cargo.toml");

    var wsClient = WsClientFactories.getDefault()
      .newClient(HttpConnector.newBuilder()
        .url(orchestrator.getServer().getUrl())
        .credentials(Server.ADMIN_LOGIN, Server.ADMIN_PASSWORD)
        .build());
    
    orchestrator.executeBuild(scanner);

    var request = new SearchRequest().setComponentKeys(List.of(projectKey));
    var issues = wsClient.issues().search(request).getIssuesList();

    assertThat(issues)
      .extracting(Issues.Issue::getLine, Issues.Issue::getComponent, Issues.Issue::getRule)
      .containsExactlyInAnyOrder(
        tuple(2, "monorepo-layout:crate1/src/main.rs", "rust:S2198"),
        tuple(2, "monorepo-layout:crate2/src/main.rs", "rust:S2198")
      );
  }

  @Test
  void testWorkspaceLayout() throws Exception {
    /**
     * Tested layout:
     *  - A workspace with multiple Rust crates and a root Cargo.toml file
     * 
     * workspace
     *  └ crate1
     *     └ src
     *        └ main.rs
     *     └ Cargo.toml
     *  └ crate2
     *     └ src
     *        └ main.rs
     *     └ Cargo.toml
     *  └ Cargo.toml
     */

    var projectKey = "workspace-layout";
    var projectName = "Workspace Layout";
    var projectDir = Paths.get(getClass().getClassLoader().getResource("projects/layouts/workspace").toURI()).toFile();

    var scanner = OrchestratorHelper.createSonarScanner()
      .setProjectKey(projectKey)
      .setProjectName(projectName)
      .setProjectDir(projectDir)
      .setProperty("sonar.internal.analysis.rust.failFast", "true")
      .setSourceDirs(".");

    var wsClient = WsClientFactories.getDefault()
      .newClient(HttpConnector.newBuilder()
        .url(orchestrator.getServer().getUrl())
        .credentials(Server.ADMIN_LOGIN, Server.ADMIN_PASSWORD)
        .build());
    
    orchestrator.executeBuild(scanner);

    var request = new SearchRequest().setComponentKeys(List.of(projectKey));
    var issues = wsClient.issues().search(request).getIssuesList();

    assertThat(issues)
      .extracting(Issues.Issue::getLine, Issues.Issue::getComponent, Issues.Issue::getRule)
      .containsExactlyInAnyOrder(
        tuple(2, "workspace-layout:crate1/src/main.rs", "rust:S2198"),
        tuple(2, "workspace-layout:crate2/src/main.rs", "rust:S2198")
      );
  }
}
