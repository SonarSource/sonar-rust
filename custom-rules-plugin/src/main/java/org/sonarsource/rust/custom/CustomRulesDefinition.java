/*
 * SonarQube Rust Plugin
 * Copyright (C) SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * You can redistribute and/or modify this program under the terms of
 * the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */
package org.sonarsource.rust.custom;

import org.sonar.api.server.rule.RulesDefinition;

/**
 * Declares the fixture plugin's rules in the {@code customrust} repository.
 *
 * <ul>
 *   <li>{@link #OVERRIDING_RULE} reuses an id that the base {@code rust} repository also defines and
 *   activates in "Sonar way", to exercise reconciliation (the contributed rule should supersede the
 *   base one).</li>
 *   <li>{@link #NEW_RULE} is a brand-new id only this plugin provides.</li>
 * </ul>
 */
public class CustomRulesDefinition implements RulesDefinition {

  static final String REPOSITORY = "customrust";
  static final String LANGUAGE = "rust";
  /** Also defined by the base {@code rust} repository and active in its "Sonar way". */
  static final String OVERRIDING_RULE = "S3776";
  /** Only defined by this plugin. */
  static final String NEW_RULE = "S9999";

  @Override
  public void define(Context context) {
    var repository = context.createRepository(REPOSITORY, LANGUAGE).setName("Custom Rust Rules");
    repository.createRule(OVERRIDING_RULE)
      .setName("Custom reimplementation of a base rule")
      .setHtmlDescription("Reuses the id of a base <code>rust</code> rule to test reconciliation.");
    repository.createRule(NEW_RULE)
      .setName("Custom-only rule")
      .setHtmlDescription("A rule that only the custom plugin provides.");
    repository.done();
  }
}
