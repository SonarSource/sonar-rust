/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.cobertura;

import com.sonarsource.rust.common.FileLocator;
import com.sonarsource.rust.coverage.CodeCoverage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonarsource.analyzer.commons.xml.SafeDomParserFactory;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class CoberturaParser {
  private static final Logger LOG = LoggerFactory.getLogger(CoberturaParser.class);
  private static final Pattern CONDITION_COVERAGE_PATTERN = Pattern.compile("([0-9]+)% \\((\\d+)/(\\d+)\\)");

  private final SensorContext context;
  private final File reportFile;
  private final Map<InputFile, CodeCoverage> coverageByFile = new HashMap<>();
  private final FileLocator fileLocator;

  public CoberturaParser(SensorContext context, File reportFile, FileLocator fileLocator) {
    this.context = context;
    this.reportFile = reportFile;
    this.fileLocator = fileLocator;
  }

  public record ParsingResult(List<CodeCoverage> coverages, List<String> problems) {}

  public ParsingResult parse() throws IOException, SAXException {
    var document = SafeDomParserFactory.createDocumentBuilder(false).parse(reportFile);

    var classes = document.getElementsByTagName("class");
    for (int i = 0; i < classes.getLength(); i++) {
      var currentClass = classes.item(i);
      var currentFileName = currentClass.getAttributes().getNamedItem("filename").getNodeValue();

      InputFile currentInputFile = resolveInputFile(currentFileName);
      var coverage = new CodeCoverage(currentInputFile);

      processClass(coverage, currentClass);

      coverageByFile.put(currentInputFile, coverage);
    }

    return new ParsingResult(new ArrayList<>(coverageByFile.values()), new ArrayList<>());
  }

  private static void processClass(CodeCoverage coverage, Node classNode) {
    var lines = getLines(classNode);
    for (int k = 0; k < lines.getLength(); k++) {
      var line = lines.item(k);
      if (line.getNodeName().equals("line")) {
        processLine(coverage, line);
      }
    }
  }

  private static NodeList getLines(Node classNode) {
    var children = classNode.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (children.item(i).getNodeName().equals("lines")) {
        return children.item(i).getChildNodes();
      }
    }
    return null;
  }

  private static void processLine(CodeCoverage coverage, Node line) {
    var lineNumber = Integer.parseInt(line.getAttributes().getNamedItem("number").getNodeValue());
    var hits = Integer.parseInt(line.getAttributes().getNamedItem("hits").getNodeValue());
    coverage.addLineHits(lineNumber, hits);

    boolean isBranch = Optional.ofNullable(line.getAttributes().getNamedItem("branch"))
      .map(node -> node.getNodeValue().equals("true"))
      .orElse(false);

    if (isBranch) {
      processConditionCoverage(coverage, line, lineNumber);
    }
  }

  private static void processConditionCoverage(CodeCoverage coverage, Node line, int lineNumber) {
    var coverageAttr = line.getAttributes().getNamedItem("condition-coverage");
    if (coverageAttr == null) {
      return;
    }

    var matcher = CONDITION_COVERAGE_PATTERN.matcher(coverageAttr.getNodeValue());
    if (!matcher.matches()) {
      return;
    }

    int taken = Integer.parseInt(matcher.group(2));
    int total = Integer.parseInt(matcher.group(3));

    for (int i = 0; i < total; i++) {
      coverage.addBranchHits(lineNumber, Integer.toString(i), i < taken ? 1 : 0);
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

}
