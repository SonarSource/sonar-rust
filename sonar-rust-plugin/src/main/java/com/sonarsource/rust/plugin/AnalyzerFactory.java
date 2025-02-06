/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.scanner.ScannerSide;
import org.sonar.api.utils.TempFolder;

import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

@ScannerSide
public class AnalyzerFactory {

  private static final Logger LOG = LoggerFactory.getLogger(AnalyzerFactory.class);

  private final TempFolder tempFolder;

  public AnalyzerFactory(TempFolder tempFolder) {
    this.tempFolder = tempFolder;
  }

  public Analyzer create() throws IOException{
    try (var stream = getClass().getResourceAsStream("/analyzer/analyzer")) {
      if (stream == null) {
        throw new IllegalStateException("Analyzer binary not found");
      }

      // save is into temporary file and set it as executable
      Path path = tempFolder.newDir().toPath();
      LOG.debug("Copying analyzer to {}", path);
      Files.copy(stream, path, StandardCopyOption.REPLACE_EXISTING);
      Files.setPosixFilePermissions(path, Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE));

      return new Analyzer(List.of(path.toString()));
    }
  }

}
