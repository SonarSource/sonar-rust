/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.clippy;

import com.sonarsource.rust.plugin.RustLanguage;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;

public class ClippySensor implements Sensor {

  private static final Logger LOG = LoggerFactory.getLogger(ClippySensor.class);

  public static final String CLIPPY_REPORT_PATHS = "sonar.rust.clippy.reportPaths";

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor
      .name("Clippy")
      .onlyOnLanguage(RustLanguage.KEY)
      .onlyWhenConfiguration(config -> config.hasKey(CLIPPY_REPORT_PATHS));
  }

  @Override
  public void execute(SensorContext context) {
    var reportPaths = context.config().getStringArray(CLIPPY_REPORT_PATHS);
    var reportFiles = ClippyReportProvider.getReportFiles(context, reportPaths);
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
        var reportDiagnostics = ClippyReportParser.parse(reportFile);
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
  }

  @SuppressWarnings("deprecation")
  private void saveIssue(SensorContext context, ClippyDiagnostic diagnostic) {
    var spans = diagnostic.message().spans();
    if (spans.isEmpty()) {
      throw new IllegalStateException("Empty spans");
    }

    var span = spans.get(0);
    var fileName = span.file_name();

    var predicates = context.fileSystem().predicates().hasPath(fileName);
    var inputFile = context.fileSystem().inputFile(predicates);
    if (inputFile == null) {
      throw new IllegalStateException("Unknown file: " + fileName);
    }

    var ruleId = diagnostic.message().code().code().substring("clippy::".length());
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

    issue.at(
      issue.newLocation()
        .on(inputFile)
        .at(inputFile.newRange(span.line_start(), span.column_start() - 1, span.line_end(), span.column_end() - 1))
        .message(diagnostic.message().message())
    );

    issue.save();
  }
}
