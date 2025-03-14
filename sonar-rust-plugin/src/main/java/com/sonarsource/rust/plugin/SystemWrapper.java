/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * This class is a wrapper around system calls to make it easier to test the code.
 */
class SystemWrapper {

  String getOsName() {
    return System.getProperty("os.name");
  }

  String getOsArch() {
    return System.getProperty("os.arch");
  }

  boolean fileExists(Path path) {
    return Files.exists(path);
  }

}
