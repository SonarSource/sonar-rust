/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.coverage;

import com.sonarsource.rust.common.FileLocator;
import com.sonarsource.rust.common.ReportProvider;
import com.sonarsource.rust.plugin.RustLanguage;
import java.nio.file.Files;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;

public class CoberturaSensor implements Sensor {

  public static final String COBERTURA_REPORT_PATHS = "sonar.rust.cobertura.reportPaths";

  private static final Logger LOG = LoggerFactory.getLogger(CoberturaSensor.class);

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor
      .name("Rust Cobertura Coverage")
      .onlyOnLanguage(RustLanguage.KEY)
      .onlyWhenConfiguration(config -> config.hasKey(COBERTURA_REPORT_PATHS));
  }

  @Override
  public void execute(SensorContext context) {
    LOG.debug("Processing Cobertura coverage reports");

    var reportFiles = new ReportProvider("Cobertura", COBERTURA_REPORT_PATHS).getReportFiles(context);
    if (reportFiles.isEmpty()) {
      LOG.warn("No Cobertura report files found");
      return;
    } else {
      LOG.debug("Found {} Cobertura report files", reportFiles.size());
    }

    var fs = context.fileSystem();
    var predicate = fs.predicates().and(
      fs.predicates().hasType(InputFile.Type.MAIN),
      fs.predicates().hasLanguage(RustLanguage.KEY));
    var fileLocator = new FileLocator(fs.inputFiles(predicate));

    var coverages = new ArrayList<CodeCoverage>();
    for (var reportFile : reportFiles) {
      try {
        LOG.debug("Parsing Cobertura report: {}", reportFile);
        var parser = new CoberturaParser(context, fileLocator, reportFile.toPath().toString());

        var parsingResult = parser.parse(Files.readString(reportFile.toPath()));
        var problems = parsingResult.problems();
        if (!problems.isEmpty()) {
          LOG.warn("Found {} problems in Cobertura report: {}. More details in verbose mode", problems.size(), reportFile);
          for (var problem : problems) {
            LOG.debug(problem);
          }
        }
        coverages.addAll(parsingResult.coverages());
      } catch (Exception e) {
        LOG.error("Failed to parse Cobertura report: " + reportFile, e);
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

    LOG.debug("Processed Corbetura coverage reports");
  }
}
