/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.coverage;

import com.sonarsource.rust.common.FileLocator;
import com.sonarsource.rust.common.ReportProvider;
import com.sonarsource.rust.plugin.RustLanguage;
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
        var parser = LCOVParser.create(context, reportFile, fileLocator);
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
        LOG.warn("Failed to parse LCOV report", e);
      }
    }

    for (var coverage : coverages) {
      try {
        LOG.debug("Saving coverage for file: {}", coverage.getInputFile());
        CoverageUtils.saveCoverage(context, coverage);
        LOG.debug("Successfully saved coverage");
      } catch (Exception e) {
        LOG.warn("Failed to save coverage", e);
      }
    }

    LOG.debug("Processed LCOV coverage reports");
  }


}
