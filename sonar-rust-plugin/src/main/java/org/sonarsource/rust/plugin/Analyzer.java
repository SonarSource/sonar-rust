/*
 * SonarQube Rust Plugin
 * Copyright (C) SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * You can redistribute and/or modify this program under the terms of
 * the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Set;
import java.util.HashSet;
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
  private final Duration projectTimeout;

  private volatile Set<String> knownCrateRoots = Set.of();

  public Analyzer(List<String> command, Map<String, String> parameters) {
    this(new ProcessWrapper(), command, parameters, Duration.ofSeconds(60));
  }

  Analyzer(ProcessWrapper process, List<String> command, Map<String, String> parameters, Duration projectTimeout) {
    this.process = process;
    this.projectTimeout = projectTimeout;
    try {
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
   * Use the analyzer subprocess to analyze a standalone crate-root snippet.
   * @throws IOException if executing the analyzer fails due to an I/O error
   */
  public AnalysisResult analyze(String code) throws IOException {
    writeString("analyze");
    writeString(code);
    return readAnalysis();
  }

  /** Retain Cargo-confirmed identities when replacing a failed project analyser. */
  public void preserveCrateRootsFrom(Analyzer previous) {
    knownCrateRoots = previous.knownCrateRoots;
  }

  private static String canonicalPath(String path) {
    Path file = Path.of(path);
    try {
      return file.toRealPath().toString();
    } catch (IOException ex) {
      return file.toAbsolutePath().normalize().toString();
    }
  }

  public AnalysisResult analyze(String path, String code) throws IOException {
    writeString(knownCrateRoots.contains(canonicalPath(path)) ? "analyze-root" : "analyze-file");
    writeString(path);
    writeString(code);
    return readAnalysis();
  }

  /** Supply Cargo roots and scanner source snapshots before analyzing files. */
  public List<String> initializeProject(List<String> manifests, Map<String, String> sources) throws IOException {
    // Bound both sending the snapshots and waiting for Cargo/the call graph.
    // Killing the process releases blocked protocol I/O before the sensor restarts it.
    try (var executor = Executors.newSingleThreadExecutor()) {
      var initialization = executor.submit(() -> initializeProjectProtocol(manifests, sources));
      try {
        return initialization.get(projectTimeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException ex) {
        initialization.cancel(true);
        close();
        throw new IOException("Rust project initialization timed out after " + projectTimeout.toMillis() + " ms", ex);
      } catch (InterruptedException ex) {
        initialization.cancel(true);
        close();
        Thread.currentThread().interrupt();
        throw new IOException("Rust project initialization interrupted", ex);
      } catch (ExecutionException ex) {
        if (ex.getCause() instanceof IOException ioException) {
          throw ioException;
        }
        throw new IOException("Rust project initialization failed", ex.getCause());
      }
    }
  }

  private List<String> initializeProjectProtocol(List<String> manifests, Map<String, String> sources) throws IOException {
    writeString("project");
    writeInt(manifests.size());
    for (String manifest : manifests) {
      writeString(manifest);
    }
    writeMap(sources);
    if (!"project-roots".equals(readString())) {
      throw new IOException("Unexpected project root discovery response");
    }
    int rootCount = inputStream.readInt();
    Set<String> roots = new HashSet<>();
    for (int i = 0; i < rootCount; i++) {
      roots.add(canonicalPath(readString()));
    }
    knownCrateRoots = Set.copyOf(roots);
    if (!"project-ready".equals(readString())) {
      throw new IOException("Unexpected project initialization response");
    }
    int count = inputStream.readInt();
    List<String> warnings = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      warnings.add(readString());
    }
    return warnings;
  }

  private AnalysisResult readAnalysis() throws IOException {
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
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    outputStream.writeInt(bytes.length);
    outputStream.write(bytes);
    outputStream.flush();
  }

  private void writeMap(Map<String, String> map) throws IOException {
    outputStream.writeInt(map.size());
    for (Map.Entry<String, String> entry : map.entrySet()) {
      writeString(entry.getKey());
      writeString(entry.getValue());
    }
    outputStream.flush();
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
