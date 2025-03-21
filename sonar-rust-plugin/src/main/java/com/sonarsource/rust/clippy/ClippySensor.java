/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.clippy;

import com.sonarsource.rust.cargo.CargoManifestProvider;
import com.sonarsource.rust.plugin.RustLanguage;
import com.sonarsource.rust.plugin.RustRulesDefinition;
import com.sonarsource.rust.plugin.Telemetry;
import java.nio.file.Path;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.rule.RuleKey;

import static com.sonarsource.rust.clippy.ClippyUtils.diagnosticToLocation;

public class ClippySensor implements Sensor {

  private static final Logger LOG = LoggerFactory.getLogger(ClippySensor.class);

  public static final String CLIPPY_ANALYSIS_ENABLED = "sonar.rust.clippy.enabled";

  private final ClippyPrerequisite clippyPrerequisite;
  private final ClippyRunner clippy;

  public ClippySensor() {
    this(new ClippyPrerequisite(), new ClippyRunner());
  }

  ClippySensor(ClippyPrerequisite clippyPrerequisite, ClippyRunner clippy) {
    this.clippyPrerequisite = clippyPrerequisite;
    this.clippy = clippy;
  }

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor
      .name("Clippy")
      .onlyWhenConfiguration(config -> config.getBoolean(CLIPPY_ANALYSIS_ENABLED).orElse(true))
      .onlyOnLanguage(RustLanguage.KEY);
  }

  @Override
  public void execute(SensorContext context) {
    if (!context.config().getBoolean(CLIPPY_ANALYSIS_ENABLED).orElse(true)) {
      LOG.debug("Clippy analysis is disabled");
      return;
    }

    var manifests = CargoManifestProvider.getManifests(context);
    if (manifests.isEmpty()) {
      LOG.warn("No Cargo manifest found, skipping Clippy analysis");
      return;
    }

    var baseDir = context.fileSystem().baseDir().toPath();

    Telemetry.reportAnalyzerClippyUsage(context);

    try {
      var versions = clippyPrerequisite.check(baseDir);
      Telemetry.reportClippyVersion(context, versions.clippyVersion());
    } catch (Exception e) {
      LOG.error("Failed to check Clippy prerequisites", e);
      return;
    }

    var lints = context.activeRules().findByRepository(RustLanguage.KEY).stream()
      .map(rule -> RustRulesDefinition.ruleKeyToLintId(rule.ruleKey().rule()))
      // Not all rules can be mapped to Clippy lints, e.g. S2260 (syntax errors)
      .filter(Objects::nonNull)
      .toList();

    try {
      for (var manifest : manifests) {
        Path manifestPath = manifest.toPath();
        Telemetry.reportManifestInfo(context, manifestPath);

        var workDir = manifestPath.getParent();
        clippy.run(workDir, lints, diagnostic -> saveIssue(context, diagnostic, workDir));
      }
    } catch (Exception e) {
      LOG.error("Failed to run Clippy", e);
    }
  }

  private static void saveIssue(SensorContext context, ClippyDiagnostic diagnostic, Path workDir) {
    LOG.debug("Saving Clippy diagnostic: {}", diagnostic);

    String lintId = diagnostic.lintId();
    String ruleKey = RustRulesDefinition.lintIdToRuleKey(lintId);
    if (ruleKey == null) {
      LOG.debug("No rule key found for Clippy lint: {}", lintId);
      return;
    }

    var issue = context.newIssue()
      .forRule(RuleKey.of(RustLanguage.KEY, ruleKey));

    var location = diagnosticToLocation(issue.newLocation(), diagnostic, context, workDir);
    if (location == null) {
      LOG.debug("No InputFile found for Clippy diagnostic: {}", diagnostic);
      return;
    }

    var message = RustRulesDefinition.lintIdToMessage(lintId);
    if (message == null) {
      LOG.debug("No message found for Clippy lint: {}", lintId);
      return;
    }

    location.message(message);

    issue.at(location);
    issue.save();
  }
}
