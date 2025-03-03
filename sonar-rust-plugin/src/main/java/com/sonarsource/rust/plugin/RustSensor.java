/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import java.io.IOException;
import java.util.List;
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

  public RustSensor(AnalyzerFactory analyzerFactory) {
    this.analyzerFactory = analyzerFactory;
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
    var platform = Platform.detect();
    if (platform == Platform.UNSUPPORTED) {
      LOG.error("Unsupported platform: {}", Platform.detect());
      return;
    }
    LOG.info("Detected platform: {}", platform);
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
      LOG.error("Failed to analyze file: {} ({})", inputFile.filename(), ex.getMessage());
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
        TextRange range = inputFile.newRange(token.startLine(), token.startColumn(), token.endLine(), token.endColumn());
        highlighting.highlight(range, TypeOfText.valueOf(token.tokenType()));
      } catch (IllegalArgumentException e) {
        LOG.error("Invalid highlighting: {}", e.getMessage());
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
        newCpdTokens.addToken(token.startLine(), token.startColumn(), token.endLine(), token.endColumn(), token.image());
      } catch (IllegalArgumentException e) {
        LOG.error("Invalid CPD token: {}", e.getMessage());
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
          .at(inputFile.newRange(issue.startLine(), issue.startColumn(), issue.endLine(), issue.endColumn()))
          .message(issue.message());
        newIssue
          .forRule(RuleKey.of(RustLanguage.KEY, issue.ruleKey()))
          .at(location)
          .save();
      } catch (IllegalArgumentException e) {
        LOG.error("Invalid issue: {}", e.getMessage());
      }
    }
  }
}
