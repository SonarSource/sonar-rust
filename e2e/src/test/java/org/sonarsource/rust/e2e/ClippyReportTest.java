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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sonarqube.ws.Issues;
import org.sonarqube.ws.client.HttpConnector;
import org.sonarqube.ws.client.WsClientFactories;
import org.sonarqube.ws.client.issues.SearchRequest;

@ExtendWith(OrchestratorHelper.class)
class ClippyReportTest {

  private static final OrchestratorExtension orchestrator = OrchestratorHelper.orchestrator();

  @Test
  void test() throws Exception {
    var projectKey = "clippy";
    var projectName = "Clippy";
    var projectDir = Paths.get(getClass().getClassLoader().getResource("projects/clippy").toURI()).toFile();

    var clippyReportPath = projectDir.toPath().resolve("report.json");
    Files.createFile(clippyReportPath);

    ProcessBuilder processBuilder = new ProcessBuilder()
      .command("cargo", "clippy", "--message-format", "json")
      .directory(projectDir)
      .redirectOutput(clippyReportPath.toFile())
      .redirectError(ProcessBuilder.Redirect.INHERIT);

    Process process = processBuilder.start();
    process.waitFor();

    var scanner = OrchestratorHelper.createSonarScanner()
      .setProjectKey(projectKey)
      .setProjectName(projectName)
      .setProjectDir(projectDir)
      .setSourceDirs("src")
      .setProperty("sonar.internal.analysis.rust.failFast", "true")
      .setProperty("sonar.rust.clippy.enabled", "false")
      .setProperty("sonar.rust.clippyReport.reportPaths", "report.json");

    var wsClient = WsClientFactories.getDefault()
      .newClient(HttpConnector.newBuilder()
        .url(orchestrator.getServer().getUrl())
        .credentials(Server.ADMIN_LOGIN, Server.ADMIN_PASSWORD)
        .build());
    
    orchestrator.executeBuild(scanner);

    var request = new SearchRequest().setComponentKeys(List.of(projectKey));
    var issues = wsClient.issues().search(request).getIssuesList();

    assertThat(issues).hasSize(2);
    assertThat(issues)
      .extracting(Issues.Issue::getLine, Issues.Issue::getComponent, Issues.Issue::getRule)
      .containsExactlyInAnyOrder(
        tuple(2, "clippy:src/main.rs", "external_clippy:double_comparisons"),
        tuple(3, "clippy:src/main.rs", "external_clippy:empty_loop"));
    
    Files.deleteIfExists(clippyReportPath);
  }
}
