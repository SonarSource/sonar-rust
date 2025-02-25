/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.sonar.api.SonarRuntime;
import org.sonar.api.server.rule.RulesDefinition;
import org.sonarsource.analyzer.commons.RuleMetadataLoader;

public class RustRulesDefinition implements RulesDefinition {

  private static final String RESOURCE_BASE_PATH = "/org/sonar/l10n/rust/rules/rust";
  private static final String SONAR_WAY_PATH = "/org/sonar/l10n/rust/rules/rust/Sonar_way_profile.json";

  public static final Map<String, String> RULES= new HashMap<>();

  static {
    RULES.put("clippy::absurd_extreme_comparisons", "S2198");
    RULES.put("clippy::approx_constant", "S6164");
    RULES.put("clippy::eq_op", "S1764");
    RULES.put("clippy::ifs_same_cond", "S1862");
    RULES.put("clippy::invalid_regex", "S5856");
    RULES.put("clippy::min_max", "S6913");
    RULES.put("clippy::no_effect", "S905");
    RULES.put("clippy::out_of_bounds_indexing", "S6466");
    RULES.put("clippy::overly_complex_bool_expr", "S2589");
    RULES.put("clippy::possible_missing_comma", "S3723");
    RULES.put("clippy::redundant_closure_for_method_calls", "S1612");
    RULES.put("clippy::self_assignment", "S1656");
    RULES.put("clippy::too_many_arguments", "S107");
    RULES.put("clippy::vec_resize_to_zero", "S7200");
    RULES.put("clippy::zero_ptr", "S4962");
  }

  private final SonarRuntime sonarRuntime;

  public RustRulesDefinition(SonarRuntime sonarRuntime) {
    this.sonarRuntime = sonarRuntime;
  }

  @Override
  public void define(Context context) {
    var loader = new RuleMetadataLoader(RESOURCE_BASE_PATH, SONAR_WAY_PATH, sonarRuntime);
    var repository = context.createRepository(RustLanguage.KEY, RustLanguage.KEY).setName("SonarAnalyzer");
    var ruleKeys = new ArrayList<>(RULES.values());
    loader.addRulesByRuleKey(repository, ruleKeys);
    repository.done();
  }
}
