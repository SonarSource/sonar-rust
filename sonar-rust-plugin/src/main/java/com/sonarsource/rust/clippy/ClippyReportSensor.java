/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.clippy;

import static com.sonarsource.rust.clippy.ClippyUtils.diagnosticToLocation;

import com.sonarsource.rust.common.ReportProvider;
import com.sonarsource.rust.plugin.RustLanguage;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;

public class ClippyReportSensor implements Sensor {

  private static final Logger LOG = LoggerFactory.getLogger(ClippyReportSensor.class);

  public static final String CLIPPY_REPORT_PATHS = "sonar.rust.clippy.reportPaths";


  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor
      .name("Clippy Report Import Sensor")
      .onlyOnLanguage(RustLanguage.KEY)
      .onlyWhenConfiguration(config -> config.hasKey(CLIPPY_REPORT_PATHS));
  }

  @Override
  public void execute(SensorContext context) {
    LOG.debug("Processing Clippy reports");

    var reportProvider = new ReportProvider("Clippy", CLIPPY_REPORT_PATHS);
    var reportFiles = reportProvider.getReportFiles(context);
    if (reportFiles.isEmpty()) {
      LOG.warn("No Clippy report files found");
      return;
    } else {
      LOG.debug("Found {} Clippy report files", reportFiles.size());
    }

    var diagnostics = new ArrayList<ClippyDiagnostic>();
    for (var reportFile : reportFiles) {
      try {
        LOG.debug("Parsing Clippy report: {}", reportFile);
        var reportDiagnostics = ClippyUtils.parse(reportFile);
        diagnostics.addAll(reportDiagnostics);
        LOG.debug("Successfully parsed Clippy report");
      } catch (Exception e) {
        LOG.warn("Failed to parse Clippy report", e);
      }
    }

    for (var diagnostic : diagnostics) {
      try {
        LOG.debug("Saving Clippy diagnostic: {}", diagnostic);
        saveIssue(context, diagnostic);
        LOG.debug("Successfully saved Clippy diagnostic");
      } catch (Exception e) {
        LOG.warn("Failed to save Clippy diagnostic. {}", e.getMessage());
      }
    }

    LOG.debug("Processed Clippy reports");
  }

  @SuppressWarnings("deprecation")
  private static void saveIssue(SensorContext context, ClippyDiagnostic diagnostic) {
    var ruleId = diagnostic.lintId().substring("clippy::".length());
    var loader = ClippyRulesDefinition.loader();
    if (!loader.ruleKeys().contains(ruleId)) {
      throw new IllegalStateException("Unknown rule: " + ruleId);
    }

    var issue = context.newExternalIssue()
      .engineId(ClippyRulesDefinition.LINTER_KEY)
      .ruleId(ruleId)
      .type(loader.ruleType(ruleId))
      .severity(loader.ruleSeverity(ruleId))
      .remediationEffortMinutes(loader.ruleConstantDebtMinutes(ruleId));

    var location = diagnosticToLocation(issue.newLocation(), diagnostic, context.fileSystem());
    if (location == null) {
      throw new IllegalStateException("Unknown file: " + diagnostic.message().spans().get(0).file_name());
    }
    issue.at(location);
    issue.save();
  }
}
