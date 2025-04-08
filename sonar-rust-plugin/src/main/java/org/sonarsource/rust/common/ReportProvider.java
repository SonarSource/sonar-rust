/*
 * SonarQube Rust Plugin
 * Copyright (C) 2025 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the Sonar Source-Available License Version 1, as published by SonarSource SA.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */
package org.sonarsource.rust.common;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonarsource.analyzer.commons.FileProvider;

public class ReportProvider {

  private static final Logger LOG = LoggerFactory.getLogger(ReportProvider.class);

  private final String kind;
  private final String property;

  public ReportProvider(String kind, String property) {
    this.kind = kind;
    this.property = property;
  }

  public List<File> getReportFiles(SensorContext context) {
    var reportPaths = context.config().getStringArray(property);
    if (reportPaths.length == 0) {
      LOG.debug("No {} report paths were provided", kind);
      return List.of();
    }

    var reportFiles = new ArrayList<File>();
    for (var reportPath : reportPaths) {
      // Resolving report files from report paths is done through two strategies:
      // 1. If a report path denotes a file, assuming it exists, we resolve the path and use it as is.
      // 2. Otherwise, we consider the report path as a pattern and resolve all matching files.

      LOG.debug("Attempting to resolve {} report path: {}", kind, reportPath);

      // Strategy 1: If the path denotes a file, we use it as is.
      var baseDir = context.fileSystem().baseDir();
      var reportFile = new File(reportPath);
      if (!reportFile.isAbsolute()) {
        reportFile = new File(baseDir, reportPath);
      }
      if (reportFile.isFile()) {
        LOG.debug("Found {} report file: {}", kind, reportFile);
        reportFiles.add(reportFile);
        continue;
      } else {
        LOG.debug("{} report path is not a file: {}", kind, reportFile);
      }

      LOG.debug("Attempting to resolve {} report pattern: {}", kind, reportPath);

      // Strategy 2: If the path denotes a pattern, we resolve all matching files.
      var provider = new FileProvider(baseDir, reportPath);
      var matchingFiles = provider.getMatchingFiles();
      if (matchingFiles.isEmpty()) {
        LOG.debug("No {} report files matched the pattern: {}", kind, reportPath);
        continue;
      } else {
        LOG.debug("Found {} report files: {}", kind, matchingFiles);
      }

      reportFiles.addAll(matchingFiles);
    }

    return reportFiles;
  }
}
