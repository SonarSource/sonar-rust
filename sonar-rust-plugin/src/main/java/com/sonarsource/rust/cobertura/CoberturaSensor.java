/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.cobertura;

import com.sonarsource.rust.common.ReportProvider;
import com.sonarsource.rust.coverage.CodeCoverage;
import com.sonarsource.rust.plugin.RustLanguage;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    var coverages = new ArrayList<CodeCoverage>();
  }
}
