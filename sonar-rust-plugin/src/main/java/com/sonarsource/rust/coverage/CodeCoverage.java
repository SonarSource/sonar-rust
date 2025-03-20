/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.coverage;

import java.util.HashMap;
import java.util.Map;
import org.sonar.api.batch.fs.InputFile;

public class CodeCoverage {

  private final InputFile inputFile;
  /* line number -> execution count */
  private final Map<Integer, Integer> lineHits = new HashMap<>();
  /* line number -> branch number -> taken */
  private final Map<Integer, Map<String, Integer>> branchHits = new HashMap<>();

  public CodeCoverage(InputFile inputFile) {
    this.inputFile = inputFile;
  }

  public InputFile getInputFile() {
    return inputFile;
  }

  public Map<Integer, Integer> getLineHits() {
    return lineHits;
  }

  public void addLineHits(int line, int hits) {
    validateLineNumber(line);
    lineHits.merge(line, hits, Integer::sum);
  }

  public Map<Integer, Map<String, Integer>> getBranchHits() {
    return branchHits;
  }

  public void addBranchHits(int line, String branch, int taken) {
    validateLineNumber(line);
    branchHits.computeIfAbsent(line, k -> new HashMap<>()).merge(branch, taken, Integer::sum);
  }

  private void validateLineNumber(int line) {
    if (line < 1 || line > inputFile.lines()) {
      throw new IllegalStateException("Line number outside of file range: " + line);
    }
  }
}
