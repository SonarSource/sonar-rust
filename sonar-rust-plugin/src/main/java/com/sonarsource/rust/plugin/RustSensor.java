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
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.batch.sensor.highlighting.NewHighlighting;
import org.sonar.api.batch.sensor.highlighting.TypeOfText;

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
      .name("Rust sensor");
  }

  @Override
  public void execute(SensorContext sensorContext) {
    List<InputFile> inputFiles = inputFiles(sensorContext);
    try {
      Analyzer analyzer = analyzerFactory.create();
      for (InputFile inputFile : inputFiles) {
        analyzeFile(analyzer, sensorContext, inputFile);
      }
    } catch (IOException ex) {
      LOG.error("Failed to execute analyzer: {}", ex.getMessage());
    }
  }

  private static void analyzeFile(Analyzer analyzer, SensorContext sensorContext, InputFile inputFile) {
    try {
      var result = analyzer.analyze(inputFile.contents());
      NewHighlighting newHighlighting = sensorContext.newHighlighting().onFile(inputFile);
      reportHighlighting(inputFile, newHighlighting, result.highlightTokens());
      newHighlighting.save();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    } catch (Exception ex) {
      LOG.error("Failed to analyze file: {} ({})", inputFile.filename(), ex.getMessage());
    }
  }

  private List<InputFile> inputFiles(SensorContext sensorContext) {
    FileSystem fileSystem = sensorContext.fileSystem();
    FilePredicate predicate = mainFilePredicate(sensorContext);
    return StreamSupport.stream(fileSystem.inputFiles(predicate).spliterator(), false)
      .toList();
  }

  protected FilePredicate mainFilePredicate(SensorContext sensorContext) {
    FileSystem fileSystem = sensorContext.fileSystem();
    return fileSystem.predicates().and(
      fileSystem.predicates().hasLanguage(RustLanguage.KEY),
      fileSystem.predicates().hasType(InputFile.Type.MAIN));
  }

  private static void reportHighlighting(InputFile inputFile, NewHighlighting highlighting, List<Analyzer.HighlightTokens> tokens) {
    for (var token : tokens) {
      try {
        TextRange range = inputFile.newRange(token.startLine(), token.startColumn(), token.endLine(), token.endColumn());
        highlighting.highlight(range, TypeOfText.valueOf(token.tokenType()));
      } catch (IllegalArgumentException e) {
        LOG.error("Invalid highlighting: {}", e.getMessage());
      }
    }
  }
}
