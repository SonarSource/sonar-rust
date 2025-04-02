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
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sonarqube.ws.client.HttpConnector;
import org.sonarqube.ws.client.WsClientFactories;
import org.sonarqube.ws.client.measures.ComponentRequest;

@ExtendWith(OrchestratorHelper.class)
class MetricsTest {

  private static final OrchestratorExtension orchestrator = OrchestratorHelper.orchestrator();

  @Test
  void test() throws Exception {
    var projectKey = "metrics";
    var projectName = "Metrics";
    var projectDir = Paths.get(getClass().getClassLoader().getResource("projects/metrics").toURI()).toFile();

    var scanner = SonarScanner.create()
      .setProjectKey(projectKey)
      .setProjectName(projectName)
      .setProjectDir(projectDir)
      .setProperty("sonar.internal.analysis.rust.failFast", "true")
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
        "comment_lines",
        "cognitive_complexity",
        "complexity"
        ));
    var measures = wsClient.measures().component(request).getComponent().getMeasuresList();
    assertThat(measures).hasSize(7);

    var metrics = measures.stream().collect(Collectors.toMap(m -> m.getMetric(), m -> m.getValue()));
    assertThat(metrics)
      .containsEntry("ncloc", "26")
      .containsEntry("classes", "2")
      .containsEntry("functions", "3")
      .containsEntry("statements", "9")
      .containsEntry("comment_lines", "9")
      .containsEntry("cognitive_complexity", "4")
      .containsEntry("complexity", "6");
  }
}
