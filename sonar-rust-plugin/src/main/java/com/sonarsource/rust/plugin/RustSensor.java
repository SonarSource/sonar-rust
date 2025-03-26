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
package com.sonarsource.rust.plugin;

import com.sonarsource.rust.plugin.PlatformDetection.Platform;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.FilePredicate;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.TextRange;
import org.sonar.api.batch.measure.Metric;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.batch.sensor.highlighting.NewHighlighting;
import org.sonar.api.batch.sensor.highlighting.TypeOfText;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.rule.RuleKey;

public class RustSensor implements Sensor {

  private static final Logger LOG = LoggerFactory.getLogger(RustSensor.class);

  private final AnalyzerFactory analyzerFactory;
  private final PlatformDetection platformDetection;

  public RustSensor(AnalyzerFactory analyzerFactory) {
    this.analyzerFactory = analyzerFactory;
    this.platformDetection = new PlatformDetection();
  }

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor
      .onlyOnLanguage(RustLanguage.KEY)
      .name("Rust");
  }

  @Override
  public void execute(SensorContext sensorContext) {
    List<InputFile> inputFiles = inputFiles(sensorContext);
    var platform = platformDetection.detect();
    if (platform == Platform.UNSUPPORTED) {
      LOG.error("Unsupported platform: {}", platformDetection.debug());
      return;
    }
    LOG.info("Detected platform: {}", platform);

    // Find rule parameters
    Map<String, String> parameters = new HashMap<>();
    for (var parameter : RustRulesDefinition.parameters()) {
      parameters.put(String.format("%s:%s", parameter.ruleKey(), parameter.paramKey()), parameter.defaultValue());
    }
    for (var activeRule : sensorContext.activeRules().findByRepository(RustLanguage.KEY)) {
      for (var parameter : activeRule.params().entrySet()) {
        parameters.put(String.format("%s:%s", activeRule.ruleKey(), parameter.getKey()), parameter.getValue());
      }
    }
    analyzerFactory.addParameters(parameters);

    try (Analyzer analyzer = analyzerFactory.create(platform)) {
      for (InputFile inputFile : inputFiles) {
        analyzeFile(analyzer, sensorContext, inputFile);
      }
    } catch (IOException ex) {
      LOG.error("Failed to create analyzer: {}", ex.getMessage());
    }
  }

  private static void analyzeFile(Analyzer analyzer, SensorContext sensorContext, InputFile inputFile) {
    try {
      var result = analyzer.analyze(inputFile.contents());

      saveMeasures(sensorContext, inputFile, result.measures());
      saveHighlighting(sensorContext, inputFile, result.highlightTokens());
      saveCPD(sensorContext, inputFile, result.cpdTokens());
      saveIssues(sensorContext, inputFile, result.issues());
    } catch (IOException ex) {
      LOG.error("Failed to analyze file: {}. Reason: {}", inputFile.filename(), ex.getMessage());
    }
  }

  private static List<InputFile> inputFiles(SensorContext sensorContext) {
    FileSystem fileSystem = sensorContext.fileSystem();
    FilePredicate predicate = fileSystem.predicates().hasLanguage(RustLanguage.KEY);
    return StreamSupport.stream(fileSystem.inputFiles(predicate).spliterator(), false)
      .toList();
  }

  private static void saveHighlighting(SensorContext sensorContext, InputFile inputFile, List<Analyzer.HighlightTokens> tokens) {
    NewHighlighting highlighting = sensorContext.newHighlighting();
    highlighting.onFile(inputFile);
    for (var token : tokens) {
      try {
        Analyzer.Location location = token.location();
        TextRange range = inputFile.newRange(location.startLine(), location.startColumn(), location.endLine(), location.endColumn());
        highlighting.highlight(range, TypeOfText.valueOf(token.tokenType()));
      } catch (IllegalArgumentException e) {
        LOG.error("Invalid highlighting: {}. Reason: {}", token, e.getMessage());
      }
    }
    highlighting.save();
  }

  private static void saveMeasures(SensorContext sensorContext, InputFile inputFile, Analyzer.Measures measures) {
    saveMetric(sensorContext, inputFile, CoreMetrics.NCLOC, measures.ncloc());
    saveMetric(sensorContext, inputFile, CoreMetrics.COMMENT_LINES, measures.commentLines());
    saveMetric(sensorContext, inputFile, CoreMetrics.FUNCTIONS, measures.functions());
    saveMetric(sensorContext, inputFile, CoreMetrics.STATEMENTS, measures.statements());
    saveMetric(sensorContext, inputFile, CoreMetrics.CLASSES, measures.classes());
    saveMetric(sensorContext, inputFile, CoreMetrics.COGNITIVE_COMPLEXITY, measures.cognitiveComplexity());
    saveMetric(sensorContext, inputFile, CoreMetrics.COMPLEXITY, measures.cyclomaticComplexity());
  }
  private static void saveMetric(SensorContext sensorContext, InputFile inputFile, Metric<Integer> metric, Integer value) {
    sensorContext.<Integer>newMeasure()
      .on(inputFile)
      .forMetric(metric)
      .withValue(value)
      .save();
  }

  private static void saveCPD(SensorContext sensorContext, InputFile inputFile, List<Analyzer.CpdToken> tokens) {
    var newCpdTokens = sensorContext.newCpdTokens().onFile(inputFile);
    for (var token : tokens) {
      try {
        newCpdTokens.addToken(token.location().startLine(), token.location().startColumn(), token.location().endLine(), token.location().endColumn(), token.image());
      } catch (IllegalArgumentException e) {
        LOG.error("Invalid CPD token: {}. Reason: {}", token, e.getMessage());
      }
    }
    newCpdTokens.save();
  }

  private static void saveIssues(SensorContext sensorContext, InputFile inputFile, List<Analyzer.Issue> issues) {
    for (var issue : issues) {
      try {
        var newIssue = sensorContext.newIssue();
        var location = newIssue.newLocation()
          .on(inputFile)
          .at(inputFile.newRange(issue.location().startLine(), issue.location().startColumn(), issue.location().endLine(), issue.location().endColumn()))
          .message(issue.message());
        newIssue
          .forRule(RuleKey.of(RustLanguage.KEY, issue.ruleKey()))
          .at(location);

        for (var secondaryLocation : issue.secondaryLocations()) {
          newIssue.addLocation(newIssue.newLocation()
            .on(inputFile)
            .at(inputFile.newRange(
              secondaryLocation.location().startLine(),
              secondaryLocation.location().startColumn(),
              secondaryLocation.location().endLine(),
              secondaryLocation.location().endColumn()))
            .message(secondaryLocation.message()));
        }

        newIssue.save();
      } catch (IllegalArgumentException e) {
        LOG.error("Invalid issue: {}. Reason: {}", issue, e.getMessage());
      }
    }
  }
}
