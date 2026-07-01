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
package org.sonar.plugins.rust.api;

import java.util.Collection;
import org.sonar.api.ExtensionPoint;
import org.sonar.api.server.ServerSide;

/**
 * Extension point allowing another plugin to contribute rules to the built-in "Sonar way"
 * quality profile owned by the base Rust plugin.
 *
 * <p>This interface lives in the {@code org.sonar.plugins.rust.api} package on purpose: SonarQube
 * loads every plugin in its own isolated classloader, with a single exception for classes located
 * in the {@code org.sonar.plugins.<pluginKey>.api} package (here the plugin key is {@code rust}),
 * which are made visible to other plugins. A plugin that implements this interface and registers
 * the implementation as an extension will have it injected into the base plugin's profile
 * definition.</p>
 *
 * <p>When a contributed rule id matches a rule already provided by the base {@code rust}
 * repository, the built-in profile activates the contributed rule instead of the base one, so the
 * contributing plugin's implementation supersedes the base one.</p>
 */
@ServerSide
@ExtensionPoint
public interface RustRulesRepository {

  /**
   * Rule keys to activate in the built-in "Sonar way" profile, each in the fully-qualified
   * {@code "repositoryKey:ruleId"} form (for example {@code "rustenterprise:S2757"}).
   *
   * @return the fully-qualified keys of the rules to activate
   */
  Collection<String> ruleKeys();
}
