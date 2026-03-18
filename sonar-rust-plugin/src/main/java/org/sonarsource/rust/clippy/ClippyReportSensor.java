/*
 * SonarQube Rust Plugin
 * Copyright (C) 2025-2026 SonarSource Sàrl
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
package org.sonarsource.rust.clippy;

import org.sonarsource.rust.common.ReportProvider;
import org.sonarsource.rust.plugin.AnalysisWarningsWrapper;
import org.sonarsource.rust.plugin.RustLanguage;
import org.sonarsource.rust.plugin.Telemetry;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;

import static org.sonarsource.rust.clippy.ClippyUtils.diagnosticToLocation;

public class ClippyReportSensor implements Sensor {

  private static final Logger LOG = LoggerFactory.getLogger(ClippyReportSensor.class);
  private static final String MISSING_REPORT_WARNING = "No Clippy report files found. Check sonar.rust.clippyReport.reportPaths.";
  private static final String INVALID_REPORT_WARNING = "At least one Clippy report could not be read or parsed. See logs for details.";
  private static final String UNKNOWN_FILE_WARNING = "Some Clippy diagnostics refer to files that are not part of the analyzed project.";
  private static final String GENERIC_IMPORT_WARNING = "Some Clippy diagnostics could not be imported; see logs for details.";

  public static final String CLIPPY_REPORT_PATHS = "sonar.rust.clippyReport.reportPaths";

  private final AnalysisWarningsWrapper analysisWarnings;

  public ClippyReportSensor(AnalysisWarningsWrapper analysisWarnings) {
    this.analysisWarnings = analysisWarnings;
  }

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor
      .name("Clippy Report Import")
      .onlyOnLanguage(RustLanguage.KEY)
      .onlyWhenConfiguration(config -> config.hasKey(CLIPPY_REPORT_PATHS));
  }

  @Override
  public void execute(SensorContext context) {
    LOG.debug("Processing Clippy reports");

    Telemetry.reportExternalClippyUsage(context);

    var reportProvider = new ReportProvider("Clippy", CLIPPY_REPORT_PATHS);
    var reportFiles = reportProvider.getReportFiles(context);
    if (reportFiles.isEmpty()) {
      LOG.warn("No Clippy report files found");
      analysisWarnings.addUnique(MISSING_REPORT_WARNING);
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
        LOG.error("Failed to parse Clippy report", e);
        analysisWarnings.addUnique(INVALID_REPORT_WARNING);
      }
    }

    for (var diagnostic : diagnostics) {
      try {
        LOG.debug("Saving Clippy diagnostic: {}", diagnostic);
        saveIssue(context, diagnostic);
        LOG.debug("Successfully saved Clippy diagnostic");
      } catch (ClippyImportException e) {
        LOG.warn("Failed to save Clippy diagnostic. {}", e.getMessage());
        addWarning(e.category());
      } catch (Exception e) {
        LOG.warn("Failed to save Clippy diagnostic. {}", e.getMessage(), e);
        analysisWarnings.addUnique(GENERIC_IMPORT_WARNING);
      }
    }

    LOG.debug("Processed Clippy reports");
  }

  private void addWarning(ClippyImportException.Category category) {
    switch (category) {
      case UNKNOWN_RULE:
        return;
      case UNKNOWN_FILE:
        analysisWarnings.addUnique(UNKNOWN_FILE_WARNING);
        return;
      case INVALID_DIAGNOSTIC:
        analysisWarnings.addUnique(GENERIC_IMPORT_WARNING);
        return;
    }
  }

  @SuppressWarnings("deprecation")
  private static void saveIssue(SensorContext context, ClippyDiagnostic diagnostic) {
    var ruleId = diagnostic.lintId().substring("clippy::".length());
    var loader = ClippyRulesDefinition.loader();
    if (!loader.ruleKeys().contains(ruleId)) {
      throw ClippyImportException.unknownRule(ruleId);
    }

    var issue = context.newExternalIssue()
      .engineId(ClippyRulesDefinition.LINTER_KEY)
      .ruleId(ruleId)
      .type(loader.ruleType(ruleId))
      .severity(loader.ruleSeverity(ruleId))
      .remediationEffortMinutes(loader.ruleConstantDebtMinutes(ruleId));

    var location = diagnosticToLocation(issue.newLocation(), diagnostic, context);
    if (location == null) {
      throw ClippyImportException.unknownFile(diagnostic.message().spans().get(0).file_name());
    }

    location.message(diagnostic.message().message());

    issue.at(location);
    issue.save();
  }
}
