/*
 * SonarQube Rust Plugin
 * Copyright (C) SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * You can redistribute and/or modify this program under the terms of
 * the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sonar.orchestrator.container.Edition;
import com.sonar.orchestrator.container.Server;
import com.sonar.orchestrator.junit5.OrchestratorExtension;
import com.sonar.orchestrator.locator.FileLocation;
import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.sonarqube.ws.Rules;
import org.sonarqube.ws.client.HttpConnector;
import org.sonarqube.ws.client.WsClient;
import org.sonarqube.ws.client.WsClientFactories;

/**
 * Exercises the {@code org.sonar.plugins.rust.api.RustRulesRepository} extension point on the
 * sonar-rust side, independently of any other real plugin: a minimal fixture plugin
 * (custom-rules-plugin) implements the API and contributes two rules to the built-in "Sonar way"
 * profile.
 *
 * <p>Asserts the two behaviours of the API: (1) contributed rules are activated in the base-owned
 * "Sonar way", and (2) reconciliation — the contributed {@code customrust:S3776} supersedes the
 * base {@code rust:S3776} (same id), which is no longer active.</p>
 */
class RustRulesRepositoryApiTest {

  private static final String CUSTOM_REPOSITORY = "customrust";
  private static final String BASE_REPOSITORY = "rust";
  private static final String OVERRIDING_RULE = "S3776";
  private static final String NEW_RULE = "S9999";

  @Test
  void custom_plugin_rules_are_contributed_to_sonar_way_and_reconciled() {
    var customPluginJar = customPluginJar();
    assumeTrue(customPluginJar != null, "custom-rules-plugin jar not built (run with -Pe2e)");

    var orchestrator = OrchestratorExtension.builderEnv()
      .setEdition(Edition.ENTERPRISE_LW)
      .activateLicense()
      .useDefaultAdminCredentialsForBuilds(true)
      .setSonarVersion(System.getProperty("sonar.runtimeVersion", "LATEST_RELEASE"))
      .addPlugin(OrchestratorHelper.basePluginLocation())
      .addPlugin(FileLocation.of(customPluginJar))
      .build();

    try {
      orchestrator.start();
      var wsClient = newAdminClient(orchestrator);
      var sonarWayKey = builtInSonarWayKey(wsClient);

      Set<String> customInProfile = activeRuleIds(wsClient, sonarWayKey, CUSTOM_REPOSITORY);
      Set<String> baseInProfile = activeRuleIds(wsClient, sonarWayKey, BASE_REPOSITORY);

      // (1) Both contributed rules are active in the base-owned "Sonar way".
      assertThat(customInProfile).contains(OVERRIDING_RULE, NEW_RULE);

      // (2) Reconciliation: the contributed rule supersedes the base rule with the same id.
      assertThat(baseInProfile).doesNotContain(OVERRIDING_RULE);
    } finally {
      orchestrator.stop();
    }
  }

  private static WsClient newAdminClient(OrchestratorExtension orchestrator) {
    return WsClientFactories.getDefault().newClient(HttpConnector.newBuilder()
      .url(orchestrator.getServer().getUrl())
      .credentials(Server.ADMIN_LOGIN, Server.ADMIN_PASSWORD)
      .build());
  }

  private static String builtInSonarWayKey(WsClient wsClient) {
    return wsClient.qualityprofiles()
      .search(new org.sonarqube.ws.client.qualityprofiles.SearchRequest().setLanguage(BASE_REPOSITORY))
      .getProfilesList().stream()
      .filter(p -> p.getIsBuiltIn() && "Sonar way".equals(p.getName()))
      .map(org.sonarqube.ws.Qualityprofiles.SearchWsResponse.QualityProfile::getKey)
      .findFirst()
      .orElseThrow(() -> new AssertionError("Built-in 'Sonar way' profile for rust not found"));
  }

  private static Set<String> activeRuleIds(WsClient wsClient, String profileKey, String repository) {
    var response = wsClient.rules().search(new org.sonarqube.ws.client.rules.SearchRequest()
      .setQprofile(profileKey)
      .setActivation("true")
      .setLanguages(List.of(BASE_REPOSITORY))
      .setPs("500"));
    return response.getRulesList().stream()
      .filter(rule -> repository.equals(rule.getRepo()))
      .map(rule -> rule.getKey().substring(rule.getKey().indexOf(':') + 1))
      .collect(Collectors.toSet());
  }

  private static File customPluginJar() {
    var jars = new File("../custom-rules-plugin/build/libs")
      .listFiles((dir, name) -> name.endsWith(".jar"));
    return jars != null && jars.length > 0 ? jars[0] : null;
  }
}
