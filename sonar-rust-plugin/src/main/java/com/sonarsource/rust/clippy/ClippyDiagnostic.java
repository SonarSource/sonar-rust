/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.clippy;

import java.util.List;

import javax.annotation.Nullable;

public record ClippyDiagnostic(@Nullable ClippyMessage message) {}
record ClippyMessage(ClippyCode code, String message, List<ClippySpan> spans) {}
record ClippyCode(String code) {}
record ClippySpan(String file_name, int line_start, int column_start, int line_end, int column_end) {}
