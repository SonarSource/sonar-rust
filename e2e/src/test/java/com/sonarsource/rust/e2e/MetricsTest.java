/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.sonar.orchestrator.build.SonarScanner;
import com.sonar.orchestrator.container.Server;
import com.sonar.orchestrator.junit5.OrchestratorExtension;
import com.sonar.orchestrator.locator.FileLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sonarqube.ws.client.HttpConnector;
import org.sonarqube.ws.client.WsClientFactories;
import org.sonarqube.ws.client.measures.ComponentRequest;
import java.io.File;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

class MetricsTest {

  @RegisterExtension
  static final OrchestratorExtension orchestrator = OrchestratorExtension.builderEnv()
    .useDefaultAdminCredentialsForBuilds(true)
    .setSonarVersion(System.getProperty("sonar.runtimeVersion", "LATEST_RELEASE"))
    .addPlugin(FileLocation.byWildcardMavenFilename(new File("../sonar-rust-plugin/build/libs"), "sonar-rust-plugin-*.jar"))
    .build();

  @Test
  void test() throws Exception {
    var projectKey = "metrics";
    var projectName = "Metrics";
    var projectDir = Paths.get(getClass().getClassLoader().getResource("projects/metrics").toURI()).toFile();

    var scanner = SonarScanner.create()
      .setProjectKey(projectKey)
      .setProjectName(projectName)
      .setProjectDir(projectDir)
      .setSourceDirs("src");

    var wsClient = WsClientFactories.getDefault()
      .newClient(HttpConnector.newBuilder()
        .url(orchestrator.getServer().getUrl())
        .credentials(Server.ADMIN_LOGIN, Server.ADMIN_PASSWORD)
        .build());

    orchestrator.executeBuild(scanner);

    var componentKey = projectKey + ":src/main.rs";
    var request = new ComponentRequest()
      .setComponent(componentKey)
      .setMetricKeys(List.of(
        "ncloc",
        "classes",
        "functions",
        "statements",
        "comment_lines"));;
    var measures = wsClient.measures().component(request).getComponent().getMeasuresList();
    assertThat(measures).hasSize(5);

    var metrics = measures.stream().collect(Collectors.toMap(m -> m.getMetric(), m -> m.getValue()));
    assertThat(metrics).containsEntry("ncloc", "15");
    assertThat(metrics).containsEntry("classes", "2");
    assertThat(metrics).containsEntry("functions", "2");
    assertThat(metrics).containsEntry("statements", "4");
    assertThat(metrics).containsEntry("comment_lines", "7");
  }
}
