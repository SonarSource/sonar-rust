/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.clippy;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonarsource.analyzer.commons.FileProvider;

public class ClippyReportProvider {

  private static final Logger LOG = LoggerFactory.getLogger(ClippyReportProvider.class);

  public static List<File> getReportFiles(SensorContext context, String[] reportPaths) {
    if (reportPaths.length == 0) {
      LOG.warn("No Clippy report paths were provided");
      return List.of();
    }

    var reportFiles = new ArrayList<File>();
    for (var reportPath : reportPaths) {
      // Resolving Clippy report files from report paths is done through two strategies:
      // 1. If a report path denotes a file, assuming it exists, we resolve the path and use it as is.
      // 2. Otherwise, we consider the report path as a pattern and resolve all matching files.

      LOG.debug("Attempting to resolve Clippy report path: {}", reportPath);

      // Strategy 1: If the path denotes a file, we use it as is.
      var baseDir = context.fileSystem().baseDir();
      var file = new File(reportPath);
      if (!file.isAbsolute()) {
        file = new File(baseDir, reportPath);
      }
      if (file.isFile()) {
        LOG.debug("Found Clippy report file: {}", file);
        reportFiles.add(file);
        continue;
      } else {
        LOG.debug("Clippy report path is not a file: {}", file);
      }

      LOG.debug("Attempting to resolve Clippy report pattern: {}", reportPath);

      // Strategy 2: If the path denotes a pattern, we resolve all matching files.
      var provider = new FileProvider(baseDir, reportPath);
      var matchingFiles = provider.getMatchingFiles();
      if (matchingFiles.isEmpty()) {
        LOG.warn("No Clippy report files matched the pattern: {}", reportPath);
        continue;
      } else {
        LOG.debug("Found Clippy report files: {}", matchingFiles);
      }

      reportFiles.addAll(matchingFiles);
    }

    return reportFiles;
  }
}
