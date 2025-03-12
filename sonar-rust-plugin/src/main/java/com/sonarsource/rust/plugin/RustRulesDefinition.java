/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import com.google.gson.Gson;
import com.sonarsource.rust.clippy.ClippyRule;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.sonar.api.SonarRuntime;
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

  public static final Set<String> SONAR_RULES = Set.of("S2260");

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
    repository.done();
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
}
