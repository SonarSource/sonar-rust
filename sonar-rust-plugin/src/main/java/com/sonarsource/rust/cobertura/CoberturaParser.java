/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.cobertura;

import com.sonarsource.rust.common.FileLocator;
import com.sonarsource.rust.coverage.CodeCoverage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.annotation.CheckForNull;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonarsource.analyzer.commons.xml.XmlFile;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class CoberturaParser {

  public record ParsingResult(List<CodeCoverage> coverages, List<String> problems) {}

  private static final Pattern CONDITION_COVERAGE_PATTERN = Pattern.compile("([0-9.]+)% \\((\\d+)/(\\d+)\\)");

  private final SensorContext context;
  private final FileLocator fileLocator;
  private final String reportFilePath;

  private final Map<InputFile, CodeCoverage> coverageByFile = new HashMap<>();
  private final List<String> problems = new ArrayList<>();

  public CoberturaParser(SensorContext context, FileLocator fileLocator, String reportFilePath) {
    this.context = context;
    this.fileLocator = fileLocator;
    this.reportFilePath = reportFilePath;
  }

  public ParsingResult parse(String content) {
    Document document = XmlFile.create(content).getDocument();
    processDocument(document);

    return new ParsingResult(new ArrayList<>(coverageByFile.values()), problems);
  }

  private void processDocument(Document document) {
    NodeList classes = document.getElementsByTagName("class");
    for (int i = 0; i < classes.getLength(); i++) {
      Node currentClass = classes.item(i);
      var currentFileName = currentClass.getAttributes().getNamedItem("filename");
      if (currentFileName == null) {
        addProblem("Attribute 'filename' not found on 'class' element", currentClass);
        continue;
      }

      InputFile currentInputFile = resolveInputFile(currentFileName.getNodeValue());
      if (currentInputFile == null) {
        addProblem("Input file not found for path: %s".formatted(currentFileName.getNodeValue()), currentClass);
        continue;
      }

      var coverage = new CodeCoverage(currentInputFile);
      processClass(coverage, currentClass);
      coverageByFile.put(currentInputFile, coverage);
    }
  }

  private void processClass(CodeCoverage coverage, Node classNode) {
    NodeList lines = getLines(classNode);
    if (lines == null) {
      return;
    }

    for (int i = 0; i < lines.getLength(); i++) {
      Node line = lines.item(i);
      if ("line".equals(line.getNodeName())) {
        processLine(coverage, line);
      }
    }
  }

  @CheckForNull
  private NodeList getLines(Node classNode) {
    var children = classNode.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (children.item(i).getNodeName().equals("lines")) {
        return children.item(i).getChildNodes();
      }
    }
    return null;
  }

  private void processLine(CodeCoverage coverage, Node line) {
    var lineNumber = extractNumber(line, "number");
    var hits = extractNumber(line, "hits");

    if (lineNumber.isEmpty() || hits.isEmpty()) {
      return;
    }

    coverage.addLineHits(lineNumber.get(), hits.get());

    boolean isBranch = Optional.ofNullable(line.getAttributes().getNamedItem("branch"))
      .map(node -> node.getNodeValue().equals("true"))
      .orElse(false);

    if (isBranch) {
      processConditionCoverage(coverage, line, lineNumber.get());
    }
  }

  private Optional<Integer> extractNumber(Node node, String attributeName) {
    var attribute = node.getAttributes().getNamedItem(attributeName);
    if (attribute == null) {
      addProblem("Attribute '%s' not found on '%s' element".formatted(attributeName, node.getNodeName()), node);
      return Optional.empty();
    }

    try {
      return Optional.of(Integer.parseInt(attribute.getNodeValue()));
    } catch (NumberFormatException ex) {
      addProblem("Invalid number format for attribute '%s' on '%s' element".formatted(attributeName, node.getNodeName()), node);
      return Optional.empty();
    }
  }

  private void processConditionCoverage(CodeCoverage coverage, Node line, int lineNumber) {
    var coverageAttr = line.getAttributes().getNamedItem("condition-coverage");
    if (coverageAttr == null) {
      addProblem("Attribute 'condition-coverage' not found on 'line' element", line);
      return;
    }

    var matcher = CONDITION_COVERAGE_PATTERN.matcher(coverageAttr.getNodeValue());
    if (!matcher.matches()) {
      addProblem("Invalid condition coverage format", line);
      return;
    }

    // The regex guarantees that these groups are integers, so the parsing cannot fail
    int taken = Integer.parseInt(matcher.group(2));
    int total = Integer.parseInt(matcher.group(3));

    if (taken > total) {
      addProblem("Invalid condition coverage: taken count is greater than total count", line);
      return;
    }

    for (int i = 0; i < total; i++) {
      coverage.addBranchHits(lineNumber, Integer.toString(i), i < taken ? 1 : 0);
    }
  }

  @CheckForNull
  private InputFile resolveInputFile(String filePath) {
    var fs = context.fileSystem();
    var predicate = fs.predicates().hasPath(filePath);
    var inputFile = fs.inputFile(predicate);
    if (inputFile == null) {
      inputFile = fileLocator.getInputFile(filePath);
    }
    return inputFile;
  }

  private void addProblem(String message, Node node) {
    var textRange = XmlFile.nodeLocation(node);
    problems.add("%s:%d:%d %s".formatted(reportFilePath, textRange.getStartLine(), textRange.getStartColumn(), message));
  }
}
