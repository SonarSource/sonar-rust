/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

public class Analyzer {

  private final List<String> command;

  public Analyzer(List<String> command) {
    this.command = command;
  }

  /**
   * Launch the analyzer subprocess and analyze the given code.
   * @throws IllegalStateException if executing the analyzer fails
   */
  public AnalysisResult analyze(String code) throws InterruptedException {
    try {
      var process = new ProcessBuilder(command)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start();
      process.getOutputStream().write(code.getBytes());
      process.getOutputStream().close();
      var output = process.getInputStream();

      Gson gson = new GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create();

      var result = gson.fromJson(new InputStreamReader(output), AnalysisResult.class);
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new IllegalStateException("Analyzer failed with exit code " + exitCode);
      }

      return result;
    } catch (IOException ex) {
      throw new IllegalStateException("I/O error occurred while running analyzer", ex);
    } catch (JsonParseException ex) {
      throw new IllegalStateException("Failed to parse analyzer output", ex);
    }
  }

  public record AnalysisResult(List<HighlightTokens> highlightTokens) {
  }

  public record HighlightTokens(String tokenType, int startLine, int startColumn, int endLine, int endColumn) {

  }

}


