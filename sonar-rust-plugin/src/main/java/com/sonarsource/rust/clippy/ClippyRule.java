/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.clippy;

public record ClippyRule(String lintId, String ruleKey, String message) {
}
