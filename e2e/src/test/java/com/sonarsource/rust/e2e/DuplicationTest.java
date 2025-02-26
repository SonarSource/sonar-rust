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
import com.sonarsource.rust.e2e.helpers.OrchestratorHelper;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;
import org.sonarqube.ws.client.HttpConnector;
import org.sonarqube.ws.client.measures.SearchRequest;
import org.sonarqube.ws.client.WsClientFactories;

class DuplicationTest {

  @RegisterExtension
  static final OrchestratorExtension orchestrator = OrchestratorHelper.createOrchestrator();

  @Test
  void test() throws Exception {
    var projectKey = "duplication";
    var projectName = "Code Duplication";
    var projectDir = Paths.get(getClass().getClassLoader().getResource("projects/duplication").toURI()).toFile();

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

    var request = new SearchRequest().setProjectKeys(List.of(projectKey)).setMetricKeys(List.of("duplicated_lines"));
    var measures = wsClient.measures().search(request).getMeasuresList();
    assertThat(measures).hasSize(1);

    var metrics = measures.stream().collect(Collectors.toMap(m -> m.getMetric(), m -> m.getValue()));
    assertThat(metrics).containsEntry("duplicated_lines", "60");
  }
}
