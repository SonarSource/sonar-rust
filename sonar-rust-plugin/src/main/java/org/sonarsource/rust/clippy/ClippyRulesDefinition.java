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
package org.sonarsource.rust.clippy;

import org.sonarsource.rust.plugin.RustLanguage;
import org.sonar.api.server.rule.RulesDefinition;
import org.sonarsource.analyzer.commons.ExternalRuleLoader;

public class ClippyRulesDefinition implements RulesDefinition {

  private static final ExternalRuleLoader loader;

  private static final String METADATA_PATH = "org/sonar/l10n/rust/rules/clippy/metadata.json";

  public static final String LINTER_KEY = "clippy";

  public static final String LINTER_NAME = "Clippy";

  static {
    loader = new ExternalRuleLoader(LINTER_KEY, LINTER_NAME, METADATA_PATH, RustLanguage.KEY, null);
  }

  @Override
  public void define(Context context) {
    loader.createExternalRuleRepository(context);
  }

  public static ExternalRuleLoader loader() {
    return loader;
  }
}
