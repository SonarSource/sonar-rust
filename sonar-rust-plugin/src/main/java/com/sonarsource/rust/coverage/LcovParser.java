/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.coverage;

import com.sonarsource.rust.common.FileLocator;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.sensor.SensorContext;

public class LcovParser {

  private static class LCOV {

    public static final String SF = "SF:";
    public static final String DA = "DA:";
    public static final String BRDA = "BRDA:";

    private LCOV() {
      // Utility class
    }
  }

  public static record ParsingResult(List<CodeCoverage> coverages, List<String> problems) {}

  /* Sensor context */
  private final SensorContext context;
  /* LCOV report file */
  private final File reportFile;
  /* LCOV report lines */
  private final List<String> lines;
  /* File locator */
  private final FileLocator fileLocator;
  /* Coverage information per file */
  private final Map<InputFile, CodeCoverage> coverageByFile = new HashMap<>();
  /* List of parsing problems */
  private final List<String> problems = new ArrayList<>();

  private LcovParser(SensorContext context, File report, List<String> lines, FileLocator fileLocator) {
    this.context = context;
    this.reportFile = report;
    this.lines = lines;
    this.fileLocator = fileLocator;
  }

  public static LcovParser create(SensorContext context, File reportFile, FileLocator fileLocator) {
    try {
      var lines = Files.readAllLines(reportFile.toPath());
      return new LcovParser(context, reportFile, lines, fileLocator);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to read LCOV report: " + reportFile, e);
    }
  }

  public ParsingResult parse() {
    CodeCoverage coverage = null;
    var lineCounter = 0;
    for (var line : lines) {
      lineCounter++;
      if (line.startsWith(LCOV.SF)) {
        coverage = parseSF(line, lineCounter);
        if (coverage != null && coverageByFile.put(coverage.getInputFile(), coverage) != null) {
          addProblem("Invalid SF. Duplicate file: " + coverage.getInputFile().toString(), lineCounter);
        }
      } else if (line.startsWith(LCOV.DA)) {
        parseDA(coverage, line, lineCounter);
      } else if (line.startsWith(LCOV.BRDA)) {
        parseBRDA(coverage, line, lineCounter);
      }
    }
    return new ParsingResult(new ArrayList<>(coverageByFile.values()), problems);
  }

  private CodeCoverage parseSF(String line, int lineCounter) {
    // SF:<absolute path to the source file>
    var filePath = line.substring(LCOV.SF.length());
    var inputFile = resolveInputFile(filePath);
    if (inputFile == null) {
      addProblem("Invalid SF. File not found: " + filePath, lineCounter);
      return null;
    }
    return new CodeCoverage(inputFile);
  }

  private void parseDA(@Nullable CodeCoverage coverage, String line, int lineCounter) {
    // DA:<line number>,<execution count>[,<checksum>]
    if (coverage == null) {
      return;
    }
    var tokens = line.substring(LCOV.DA.length()).split(",");
    if (tokens.length < 2) {
      addProblem("Invalid DA. Syntax error", lineCounter);
    } else {
      try {
        var lineId = Integer.parseInt(tokens[0]);
        var executionCount = Integer.parseInt(tokens[1]);
        coverage.addLineHits(lineId, executionCount);
      } catch (NumberFormatException e) {
        addProblem("Invalid DA. Number format error", lineCounter);
      } catch (Exception e) {
        addProblem("Invalid DA. " + e.getMessage(), lineCounter);
      }
    }
  }

  private void parseBRDA(@Nullable CodeCoverage coverage, String line, int lineCounter) {
    // BRDA:<line number>,<block number>,<branch number>,<taken>
    if (coverage == null) {
      return;
    }
    var tokens = line.substring(LCOV.BRDA.length()).split(",");
    if (tokens.length < 4) {
      addProblem("Invalid BRDA. Syntax error", lineCounter);
    } else {
      try {
        var lineId = Integer.parseInt(tokens[0]);
        var branchId = tokens[1] + tokens[2];
        var taken = Math.max(0, Integer.parseInt(tokens[3]));
        coverage.addBranchHits(lineId, branchId, taken);
      } catch (NumberFormatException e) {
        addProblem("Invalid BRDA. Number format error", lineCounter);
      } catch (Exception e) {
        addProblem("Invalid BRDA. " + e.getMessage(), lineCounter);
      }
    }
  }

  private InputFile resolveInputFile(String filePath) {
    var fs = context.fileSystem();
    var predicate = fs.predicates().hasPath(filePath);
    var inputFile = fs.inputFile(predicate);
    if (inputFile == null) {
      inputFile = fileLocator.getInputFile(filePath);
    }
    return inputFile;
  }

  private void addProblem(String message, int lineCounter) {
    problems.add(reportFile.getPath() + ":" + lineCounter + ": " + message);
  }
}
