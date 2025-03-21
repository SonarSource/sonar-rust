/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import com.sonarsource.rust.plugin.PlatformDetection.Platform;
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
import org.tukaani.xz.XZInputStream;

import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;

@ScannerSide
public class AnalyzerFactory {

  private static final Logger LOG = LoggerFactory.getLogger(AnalyzerFactory.class);

  private final TempFolder tempFolder;

  public AnalyzerFactory(TempFolder tempFolder) {
    this.tempFolder = tempFolder;
  }

  Analyzer create(Platform platform) throws IOException {
    String pathInJar = pathInJar(platform);
    LOG.debug("Extracting analyzer from {}", pathInJar);
    try (var stream = getClass().getResourceAsStream(pathInJar)) {
      if (stream == null) {
        throw new IllegalStateException("Analyzer binary not found");
      }

      // Save the stream into temporary file and set it as executable
      String suffix = platform == Platform.WIN_X64 ? ".exe" : "";
      Path path = tempFolder.newFile("analyzer-", suffix).toPath();
      LOG.debug("Copying analyzer to {}", path);
      Files.copy(new XZInputStream(stream), path, StandardCopyOption.REPLACE_EXISTING);
      if (!Files.isExecutable(path)) {
        Files.setPosixFilePermissions(path, Set.of(OWNER_EXECUTE));
      }
      return new Analyzer(List.of(path.toString()));
    }
  }

  static String pathInJar(Platform platform) {
    return switch (platform) {
      case WIN_X64 -> "/analyzer/win-x64/analyzer.exe.xz";
      case LINUX_X64 -> "/analyzer/linux-x64/analyzer.xz";
      case LINUX_X64_MUSL -> "/analyzer/linux-x64-musl/analyzer.xz";
      case LINUX_AARCH64 -> "/analyzer/linux-aarch64-musl/analyzer.xz";
      case DARWIN_AARCH64 -> "/analyzer/darwin-aarch64/analyzer.xz";
      case DARWIN_X86_64 -> "/analyzer/darwin-x86_64/analyzer.xz";
      default -> throw new IllegalStateException("Unsupported platform");
    };
  }

}
