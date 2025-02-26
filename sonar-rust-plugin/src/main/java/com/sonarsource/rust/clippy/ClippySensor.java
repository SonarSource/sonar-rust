/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.clippy;

import static com.sonarsource.rust.clippy.ClippyUtils.diagnosticToLocation;

import com.sonarsource.rust.plugin.RustLanguage;
import com.sonarsource.rust.plugin.RustRulesDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.rule.RuleKey;

public class ClippySensor implements Sensor {

  private static final Logger LOG = LoggerFactory.getLogger(ClippySensor.class);

  private final ClippyRunner clippy;

  public ClippySensor() {
    this(new ClippyRunner());
  }

  ClippySensor(ClippyRunner clippy) {
    this.clippy = clippy;
  }

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor
      .name("Clippy Sensor")
      .onlyOnLanguage(RustLanguage.KEY);
  }

  @Override
  public void execute(SensorContext context) {
    var lints = context.activeRules().findByRepository(RustLanguage.KEY).stream()
      .map(rule -> sonarKeyToLint(rule.ruleKey()))
      .toList();

    try {
      var diagnostics = clippy.run(context.fileSystem().baseDir().toPath(), lints);
      for (var diagnostic : diagnostics) {
        saveIssue(context, diagnostic);
      }
    } catch (Exception e) {
      LOG.error("Failed to run Clippy", e);
    }
  }

  static String sonarKeyToLint(RuleKey sonarKey) {
    String lintId = RustRulesDefinition.ruleKeyToLintId(sonarKey.rule());
    if (lintId == null) {
      throw new IllegalStateException("No mapping found for rule " + sonarKey);
    }
    return lintId;
  }

  private static void saveIssue(SensorContext context, ClippyDiagnostic diagnostic) {
    LOG.debug("Saving Clippy diagnostic: {}", diagnostic);
    String lintId = diagnostic.lintId();
    String ruleKey = RustRulesDefinition.lintIdToRuleKey(lintId);
    if (ruleKey == null) {
      LOG.debug("No rule key found for Clippy lint: {}", lintId);
      return;
    }
    var issue = context.newIssue()
      .forRule(RuleKey.of(RustLanguage.KEY, ruleKey));
    var location = diagnosticToLocation(issue.newLocation(), diagnostic, context.fileSystem());
    if (location == null) {
      LOG.debug("No InputFile found for Clippy diagnostic: {}", diagnostic);
      return;
    }
    issue.at(location);
    issue.save();
  }
}
