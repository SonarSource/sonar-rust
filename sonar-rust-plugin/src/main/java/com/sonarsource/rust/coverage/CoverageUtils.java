/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.coverage;

import org.sonar.api.batch.sensor.SensorContext;

public class CoverageUtils {

  private CoverageUtils() {
    // Utility class
  }

  public static void saveCoverage(SensorContext context, CodeCoverage coverage) {
    var newCoverage = context.newCoverage()
      .onFile(coverage.getInputFile());

    for (var entry : coverage.getLineHits().entrySet()) {
      newCoverage.lineHits(entry.getKey(), entry.getValue());
    }

    for (var entry : coverage.getBranchHits().entrySet()) {
      var line = entry.getKey();
      var conditions = entry.getValue().size();
      var coveredConditions = 0;
      for (var taken : entry.getValue().values()) {
        if (taken > 0) {
          coveredConditions++;
        }
      }

      newCoverage.conditions(line, conditions, coveredConditions);
      newCoverage.lineHits(line, coverage.getLineHits().getOrDefault(line, 0) + coveredConditions);
    }

    newCoverage.save();
  }

}
