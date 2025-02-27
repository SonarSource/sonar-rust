/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import static java.util.Map.entry;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.sonar.api.SonarRuntime;
import org.sonar.api.server.rule.RulesDefinition;
import org.sonarsource.analyzer.commons.RuleMetadataLoader;

public class RustRulesDefinition implements RulesDefinition {

  private static final String RESOURCE_BASE_PATH = "/org/sonar/l10n/rust/rules/rust";
  private static final String SONAR_WAY_PATH = "/org/sonar/l10n/rust/rules/rust/Sonar_way_profile.json";

  static final Map<String, String> CLIPPY_RULES = Map.ofEntries(
    entry("clippy::absurd_extreme_comparisons", "S2198"),
    entry("clippy::approx_constant", "S6164"),
    entry("clippy::eq_op", "S1764"),
    entry("clippy::ifs_same_cond", "S1862"),
    entry("clippy::invalid_regex", "S5856"),
    entry("clippy::min_max", "S6913"),
    entry("clippy::no_effect", "S905"),
    entry("clippy::out_of_bounds_indexing", "S6466"),
    entry("clippy::overly_complex_bool_expr", "S2589"),
    entry("clippy::possible_missing_comma", "S3723"),
    entry("clippy::redundant_closure_for_method_calls", "S1612"),
    entry("clippy::self_assignment", "S1656"),
    entry("clippy::too_many_arguments", "S107"),
    entry("clippy::vec_resize_to_zero", "S7200"),
    entry("clippy::zero_ptr", "S4962")
  );

  private static final Map<String, String> RULE_KEY_TO_LINT_ID = CLIPPY_RULES.entrySet().stream()
    .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

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
    ruleKeys.addAll(CLIPPY_RULES.values());
    ruleKeys.addAll(SONAR_RULES);
    loader.addRulesByRuleKey(repository, ruleKeys);
    repository.done();
  }

  public static String lintIdToRuleKey(String lintId) {
    return CLIPPY_RULES.get(lintId);
  }

  public static String ruleKeyToLintId(String ruleKey) {
    return RULE_KEY_TO_LINT_ID.get(ruleKey);
  }
}
