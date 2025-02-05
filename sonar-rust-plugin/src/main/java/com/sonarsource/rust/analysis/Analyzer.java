package com.sonarsource.rust.analysis;

import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;

public class Analyzer {

  private final Path executable;

  Analyzer() {
    // extract analyzer from resource from classpath
    try (var is = getClass().getResourceAsStream("/analyzer/analyzer")) {
      // save is into temporary file and set it as executable
      executable = Files.createTempFile("analyzer", "analyzer");
      Files.copy(is, executable, StandardCopyOption.REPLACE_EXISTING);
      Files.setPosixFilePermissions(executable, Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }


  public AnalysisResult analyze(String code) {
    // run the analyzer
    try {
      var process = new ProcessBuilder(executable.toString())
        .start();
      process.getOutputStream().write(code.getBytes());
      process.getOutputStream().close();
      var output = process.getInputStream();
      Gson gson = new GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create();
      var result = gson.fromJson(new InputStreamReader(output), AnalysisResult.class);
      process.waitFor();
      return result;
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  public record AnalysisResult(List<HighlightTokens> highlightTokens) {
  }

  record HighlightTokens(String tokenType, int startLine, int startColumn, int endLine, int endColumn) {

  }

}


