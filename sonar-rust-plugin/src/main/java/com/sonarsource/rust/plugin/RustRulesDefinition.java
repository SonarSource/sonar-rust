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
package com.sonarsource.rust.plugin;

import com.google.gson.Gson;
import com.sonarsource.rust.clippy.ClippyRule;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.sonar.api.SonarRuntime;
import org.sonar.api.server.rule.RuleParamType;
import org.sonar.api.server.rule.RulesDefinition;
import org.sonarsource.analyzer.commons.RuleMetadataLoader;

public class RustRulesDefinition implements RulesDefinition {

  private static final String RESOURCE_BASE_PATH = "/org/sonar/l10n/rust/rules/rust";
  private static final String CLIPPY_RULES_PATH = "/org/sonar/l10n/rust/rules/clippy/rules.json";
  private static final String SONAR_WAY_PATH = "/org/sonar/l10n/rust/rules/rust/Sonar_way_profile.json";

  private static final Map<String, String> RULE_KEY_TO_LINT_ID = new HashMap<>();

  static final Map<String, ClippyRule> CLIPPY_RULES = new HashMap<>();

  static {
    try {
      var stream = RustRulesDefinition.class.getResourceAsStream(CLIPPY_RULES_PATH);
      var reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
      var rules = new Gson().fromJson(reader, ClippyRule[].class);
      for (var rule : rules) {
        if (rule.ruleKey() != null) {
          CLIPPY_RULES.put(rule.lintId(), rule);
          RULE_KEY_TO_LINT_ID.put(rule.ruleKey(), rule.lintId());
        }
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to load Clippy rules resource", e);
    }
  }

  public static final Set<String> SONAR_RULES = Set.of("S2260", "S3776");

  private final SonarRuntime sonarRuntime;

  public RustRulesDefinition(SonarRuntime sonarRuntime) {
    this.sonarRuntime = sonarRuntime;
  }

  @Override
  public void define(Context context) {
    var loader = new RuleMetadataLoader(RESOURCE_BASE_PATH, SONAR_WAY_PATH, sonarRuntime);
    var repository = context.createRepository(RustLanguage.KEY, RustLanguage.KEY).setName("SonarAnalyzer");
    var ruleKeys = new ArrayList<String>();
    ruleKeys.addAll(CLIPPY_RULES.values().stream().map(ClippyRule::ruleKey).toList());
    ruleKeys.addAll(SONAR_RULES);

    loader.addRulesByRuleKey(repository, ruleKeys);

    for (var param : parameters()) {
      repository.rule(param.ruleKey())
        .createParam(param.paramKey())
        .setDefaultValue(param.defaultValue())
        .setDescription(param.description())
        .setType(param.type());
    }

    repository.done();
  }

  public static List<RuleParameter> parameters() {
    return List.of(
      new RuleParameter("S3776", "threshold", "15", "The maximum authorized complexity", RuleParamType.INTEGER)
    );
  }

  public static String lintIdToRuleKey(String lintId) {
    return Optional.ofNullable(CLIPPY_RULES.get(lintId)).map(ClippyRule::ruleKey).orElse(null);
  }

  public static String ruleKeyToLintId(String ruleKey) {
    return RULE_KEY_TO_LINT_ID.get(ruleKey);
  }

  public static String lintIdToMessage(String lintId) {
    return Optional.ofNullable(CLIPPY_RULES.get(lintId)).map(ClippyRule::message).orElse(null);
  }


  public record RuleParameter(
    String ruleKey,
    String paramKey,
    String defaultValue,
    String description,
    RuleParamType type) {
  }
}
