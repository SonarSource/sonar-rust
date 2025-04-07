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

import org.sonar.api.notifications.AnalysisWarnings;
import org.sonar.api.scanner.ScannerSide;
import org.sonarsource.api.sonarlint.SonarLintSide;

/**
 * This class is a wrapper around {@link AnalysisWarnings} to make it easier to use when AnalysisWarnings are not implemented
 * by the runtime (e.g. SonarQube IDE).
 */
@ScannerSide
@SonarLintSide
public class AnalysisWarningsWrapper {

  private final AnalysisWarnings warnings;

  public AnalysisWarningsWrapper() {
    this.warnings = null;
  }

  public AnalysisWarningsWrapper(AnalysisWarnings warnings) {
    this.warnings = warnings;
  }

  public void addUnique(String text) {
    if (warnings != null) {
      warnings.addUnique(text);
    }
  }
}
