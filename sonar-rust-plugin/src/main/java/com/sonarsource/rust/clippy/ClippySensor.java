/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.clippy;

import static com.sonarsource.rust.clippy.ClippyUtils.diagnosticToLocation;

import com.sonarsource.rust.plugin.RustLanguage;
import com.sonarsource.rust.plugin.RustRulesDefinition;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.rule.RuleKey;

public class ClippySensor implements Sensor {

  private static final Logger LOG = LoggerFactory.getLogger(ClippySensor.class);

  // TODO load from resources
  private static final Map<String, String> SONARKEY_TO_LINTS = RustRulesDefinition.RULES.entrySet().stream()
    .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor
      .name("Clippy Sensor")
      .onlyOnLanguage(RustLanguage.KEY);
  }

  @Override
  public void execute(SensorContext context) {
    var lints = context.activeRules().findByLanguage(RustLanguage.KEY).stream()
      .map(rule -> sonarKeyToLint(rule.ruleKey()))
      .map(lint -> String.format("-W%s", lint))
      .toList();

    var clippy = new ClippyRunner(context.fileSystem().baseDir().toPath(), lints);
    try {
      var diagnostics = clippy.run();
      for (var diagnostic : diagnostics) {
        saveIssue(context, diagnostic);
      }
    } catch (Exception e) {
      LOG.error("Failed to run Clippy", e);
    }
  }

  private static String sonarKeyToLint(RuleKey sonarKey) {
    String clippyKey = SONARKEY_TO_LINTS.get(sonarKey.rule());
    if (clippyKey == null) {
      throw new IllegalStateException("No mapping found for rule " + sonarKey);
    }
    return clippyKey;
  }

  private static void saveIssue(SensorContext context, ClippyDiagnostic diagnostic) {
    LOG.debug("Saving Clippy diagnostic: {}", diagnostic);
    String lintId = diagnostic.lintId();
    String ruleKey = RustRulesDefinition.RULES.get(lintId);
    if (ruleKey == null) {
      LOG.debug("No rule key found for Clippy lint: {}", lintId);
      return;
    }
    var issue = context.newIssue()
      .forRule(RuleKey.of(RustLanguage.KEY, ruleKey));
    var location = diagnosticToLocation(issue.newLocation(), diagnostic, context.fileSystem());
    issue.at(location);
    issue.save();
  }
}
