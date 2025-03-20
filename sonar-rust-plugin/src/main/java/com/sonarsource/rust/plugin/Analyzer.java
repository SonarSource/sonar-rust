/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import com.sonarsource.rust.common.ProcessWrapper;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Analyzer implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(Analyzer.class);

  private final ProcessWrapper process;
  private final DataOutputStream outputStream;
  private final DataInputStream inputStream;

  public Analyzer(List<String> command) {
    try {
      process = new ProcessWrapper();
      process.start(command, null, null, LOG::warn);
      this.outputStream = new DataOutputStream(process.getOutputStream());
      this.inputStream = new DataInputStream(process.getInputStream());
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
        int startLine = inputStream.readInt();
        int startColumn = inputStream.readInt();
        int endLine = inputStream.readInt();
        int endColumn = inputStream.readInt();
        highlightTokens.add(new HighlightTokens(tokenType, startLine, startColumn, endLine, endColumn));
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
        int startLine = inputStream.readInt();
        int startColumn = inputStream.readInt();
        int endLine = inputStream.readInt();
        int endColumn = inputStream.readInt();
        cpdTokens.add(new CpdToken(image, startLine, startColumn, endLine, endColumn));
      } else if ("issue".endsWith(messageType)) {
        String ruleKey = readString();
        String message = readString();
        int startLine = inputStream.readInt();
        int startColumn = inputStream.readInt();
        int endLine = inputStream.readInt();
        int endColumn = inputStream.readInt();
        issues.add(new Issue(ruleKey, message, startLine, startColumn, endLine, endColumn));
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

  public record AnalysisResult(List<HighlightTokens> highlightTokens, Measures measures, List<CpdToken> cpdTokens, List<Issue> issues) {
  }

  public record HighlightTokens(String tokenType, int startLine, int startColumn, int endLine, int endColumn) {
  }

  public record Measures(int ncloc, int commentLines, int functions, int statements, int classes, int cognitiveComplexity, int cyclomaticComplexity) {
    public Measures() {
      this(0, 0, 0, 0, 0, 0, 0);
    }
  }

  public record CpdToken(String image, int startLine, int startColumn, int endLine, int endColumn) {
  }

  public record Issue(String ruleKey, String message, int startLine, int startColumn, int endLine, int endColumn) {
  }
}
