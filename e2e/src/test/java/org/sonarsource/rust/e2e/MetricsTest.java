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

import static org.assertj.core.api.Assertions.assertThat;

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

    var scanner = OrchestratorHelper.createSonarScanner()
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
