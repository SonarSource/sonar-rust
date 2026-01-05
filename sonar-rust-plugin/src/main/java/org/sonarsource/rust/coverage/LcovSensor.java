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
package org.sonarsource.rust.coverage;

import org.sonarsource.rust.common.FileLocator;
import org.sonarsource.rust.common.ReportProvider;
import org.sonarsource.rust.plugin.RustLanguage;
import org.sonarsource.rust.plugin.Telemetry;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.InputFile.Type;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;

public class LcovSensor implements Sensor {

  private static final Logger LOG = LoggerFactory.getLogger(LcovSensor.class);

  public static final String COVERAGE_REPORT_PATHS = "sonar.rust.lcov.reportPaths";

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor
      .name("Rust LCOV Coverage")
      .onlyOnLanguage(RustLanguage.KEY)
      .onlyWhenConfiguration(config -> config.hasKey(COVERAGE_REPORT_PATHS));
  }

  @Override
  public void execute(SensorContext context) {
    LOG.debug("Processing LCOV coverage reports");

    Telemetry.reportCoverageFormat(context, "LCOV");

    var reportProvider = new ReportProvider("LCOV", COVERAGE_REPORT_PATHS);
    var reportFiles = reportProvider.getReportFiles(context);
    if (reportFiles.isEmpty()) {
      LOG.warn("No LCOV report files found");
      return;
    } else {
      LOG.debug("Found {} LCOV report files", reportFiles.size());
    }

    var fs = context.fileSystem();
    var predicate = fs.predicates().and(
      fs.predicates().hasType(Type.MAIN),
      fs.predicates().hasLanguage(RustLanguage.KEY));
    var fileLocator = new FileLocator(fs.inputFiles(predicate));

    var coverages = new ArrayList<CodeCoverage>();
    for (var reportFile : reportFiles) {
      try {
        LOG.debug("Parsing LCOV report: {}", reportFile);
        var parser = LcovParser.create(context, reportFile, fileLocator);
        var parsingResult = parser.parse();
        var problems = parsingResult.problems();
        if (!problems.isEmpty()) {
          LOG.warn("Found {} problems in LCOV report: {}. More details in verbose mode", problems.size(), reportFile);
          problems.forEach(LOG::debug);
        } else {
          LOG.debug("Successfully parsed LCOV report");
        }
        coverages.addAll(parsingResult.coverages());
      } catch (Exception e) {
        LOG.error("Failed to parse LCOV report", e);
      }
    }

    for (var coverage : coverages) {
      try {
        LOG.debug("Saving coverage for file: {}", coverage.getInputFile());
        CoverageUtils.saveCoverage(context, coverage);
        LOG.debug("Successfully saved coverage");
      } catch (Exception e) {
        LOG.error("Failed to save coverage", e);
      }
    }

    LOG.debug("Processed LCOV coverage reports");
  }
}
