/*
 * SonarQube Rust Plugin
 * Copyright (C) SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */
package org.sonarsource.rust.clippy;

class ClippyImportException extends RuntimeException {

  enum Category {
    UNKNOWN_RULE,
    UNKNOWN_FILE,
    INVALID_DIAGNOSTIC
  }

  private final Category category;

  private ClippyImportException(Category category, String message) {
    super(message);
    this.category = category;
  }

  private ClippyImportException(Category category, String message, Throwable cause) {
    super(message, cause);
    this.category = category;
  }

  Category category() {
    return category;
  }

  static ClippyImportException unknownRule(String ruleId) {
    return new ClippyImportException(Category.UNKNOWN_RULE, "Unknown rule: " + ruleId);
  }

  static ClippyImportException unknownFile(String fileName) {
    return new ClippyImportException(Category.UNKNOWN_FILE, "Unknown file: " + fileName);
  }

  static ClippyImportException invalidDiagnostic(String message) {
    return new ClippyImportException(Category.INVALID_DIAGNOSTIC, message);
  }

  static ClippyImportException invalidDiagnostic(String message, Throwable cause) {
    return new ClippyImportException(Category.INVALID_DIAGNOSTIC, message, cause);
  }
}
