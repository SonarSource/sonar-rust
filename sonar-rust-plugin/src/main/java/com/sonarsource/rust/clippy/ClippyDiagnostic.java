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
package com.sonarsource.rust.clippy;

import static java.util.Objects.requireNonNull;

import java.util.List;
import javax.annotation.Nullable;

public record ClippyDiagnostic(@Nullable ClippyMessage message) {
  public String lintId() {
    requireNonNull(message, "message is null");
    return message.code().code();
  }
}
record ClippyMessage(ClippyCode code, String message, List<ClippySpan> spans) {}
record ClippyCode(String code) {}
record ClippySpan(String file_name, int line_start, int column_start, int line_end, int column_end) {}
