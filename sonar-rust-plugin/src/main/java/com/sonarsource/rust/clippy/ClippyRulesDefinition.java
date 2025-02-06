/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.clippy;

import com.sonarsource.rust.plugin.RustLanguage;
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
