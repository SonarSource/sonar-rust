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
package org.sonarsource.rust.coverage;

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
