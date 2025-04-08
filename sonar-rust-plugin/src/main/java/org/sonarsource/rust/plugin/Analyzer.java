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
package org.sonarsource.rust.plugin;

import org.sonarsource.rust.common.ProcessWrapper;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Analyzer implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(Analyzer.class);

  private final ProcessWrapper process;
  private final DataOutputStream outputStream;
  private final DataInputStream inputStream;

  public Analyzer(List<String> command, Map<String, String> parameters) {
    try {
      process = new ProcessWrapper();
      process.start(command, null, null, LOG::warn);
      this.outputStream = new DataOutputStream(process.getOutputStream());
      this.inputStream = new DataInputStream(process.getInputStream());

      writeString("sonar");
      writeMap(parameters);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to start the analyzer process", ex);
    }
  }

  /**
   * Use the analyzer subprocess to analyze the given code.
   * @throws IOException if executing the analyzer fails due to an I/O error
   */
  public AnalysisResult analyze(String code) throws IOException {
    writeString("analyze");

    byte[] bytes = code.getBytes(StandardCharsets.UTF_8);
    writeInt(bytes.length);
    write(bytes);

    List<HighlightTokens> highlightTokens = new ArrayList<>();
    Measures measures = new Measures();
    List<CpdToken> cpdTokens = new ArrayList<>();
    List<Issue> issues = new ArrayList<>();

    while (true) {
      String messageType = readString();
      if ("highlight".equals(messageType)) {
        String tokenType = readString();
        Location location = readLocation();
        highlightTokens.add(new HighlightTokens(tokenType, location));
      } else if ("metrics".equals(messageType)) {
        int ncloc = inputStream.readInt();
        int commentLines = inputStream.readInt();
        int functions = inputStream.readInt();
        int statements = inputStream.readInt();
        int classes = inputStream.readInt();
        int cognitiveComplexity = inputStream.readInt();
        int cyclomaticComplexity = inputStream.readInt();

        measures = new Measures(ncloc, commentLines, functions, statements, classes, cognitiveComplexity, cyclomaticComplexity);
      } else if ("cpd".equals(messageType)) {
        String image = readString();
        Location location = readLocation();
        cpdTokens.add(new CpdToken(image, location));
      } else if ("issue".endsWith(messageType)) {
        String ruleKey = readString();
        String message = readString();
        Location location = readLocation();
        int numSecondaryLocations = inputStream.readInt();

        List<SecondaryLocation> secondaryLocations = new ArrayList<>();
        for (int i = 0; i < numSecondaryLocations; i++) {
          String secondaryMessage = readString();
          Location secondaryLocation = readLocation();
          secondaryLocations.add(new SecondaryLocation(secondaryMessage, secondaryLocation));
        }

        issues.add(new Issue(ruleKey, message, location, secondaryLocations));
      } else {
        break;
      }
    }

    return new AnalysisResult(highlightTokens, measures, cpdTokens, issues);
  }

  @Override
  public void close() {
    process.destroyForcibly();
  }

  private String readString() throws IOException {
    int length = inputStream.readInt();
    byte[] bytes = new byte[length];
    inputStream.readFully(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private Location readLocation() throws IOException {
    int startLine = inputStream.readInt();
    int startColumn = inputStream.readInt();
    int endLine = inputStream.readInt();
    int endColumn = inputStream.readInt();

    return new Location(startLine, startColumn, endLine, endColumn);
  }

  private void writeInt(int value) throws IOException {
    outputStream.writeInt(value);
    outputStream.flush();
  }

  private void writeString(String value) throws IOException {
    outputStream.writeInt(value.length());
    outputStream.write(value.getBytes(StandardCharsets.UTF_8));
    outputStream.flush();
  }

  private void write(byte[] bytes) throws IOException {
    outputStream.write(bytes);
    outputStream.flush();
  }

  private void writeMap(Map<String, String> map) throws IOException {
    outputStream.writeInt(map.size());
    for (Map.Entry<String, String> entry : map.entrySet()) {
      writeString(entry.getKey());
      writeString(entry.getValue());
    }
  }

  public record AnalysisResult(List<HighlightTokens> highlightTokens, Measures measures, List<CpdToken> cpdTokens, List<Issue> issues) {
  }

  public record HighlightTokens(String tokenType, Location location) {
  }

  public record Measures(int ncloc, int commentLines, int functions, int statements, int classes, int cognitiveComplexity, int cyclomaticComplexity) {
    public Measures() {
      this(0, 0, 0, 0, 0, 0, 0);
    }
  }

  public record CpdToken(String image, Location location) {
  }

  public record Location(int startLine, int startColumn, int endLine, int endColumn) {

  }

  public record Issue(String ruleKey, String message, Location location, List<SecondaryLocation> secondaryLocations) {
  }

  public record SecondaryLocation(String message, Location location) {

  }
}
