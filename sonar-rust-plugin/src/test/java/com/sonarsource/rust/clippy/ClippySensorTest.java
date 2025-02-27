/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.clippy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sonarsource.rust.plugin.RustLanguage;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.sonar.api.batch.fs.internal.TestInputFileBuilder;
import org.sonar.api.batch.rule.ActiveRules;
import org.sonar.api.batch.rule.internal.ActiveRulesBuilder;
import org.sonar.api.batch.rule.internal.NewActiveRule;
import org.sonar.api.batch.sensor.internal.DefaultSensorDescriptor;
import org.sonar.api.batch.sensor.internal.SensorContextTester;
import org.sonar.api.batch.sensor.issue.Issue;
import org.sonar.api.rule.RuleKey;

class ClippySensorTest {

  @TempDir
  Path baseDir;

  @Test
  void testDescribe() {
    var sensor = new ClippySensor();
    var descriptor = new DefaultSensorDescriptor();
    sensor.describe(descriptor);

    assertThat(descriptor.name()).isEqualTo("Clippy Sensor");
    assertThat(descriptor.languages()).containsOnly(RustLanguage.KEY);
  }

  @Test
  void execute_runsClippyAndSavesIssues() {
    var context = SensorContextTester.create(baseDir);
    ActiveRules activeRules = new ActiveRulesBuilder()
      .addRule(new NewActiveRule.Builder().setRuleKey(RuleKey.of(RustLanguage.KEY, "S2198")).build())
      .build();
    context.setActiveRules(activeRules);
    var inputFile = new TestInputFileBuilder("moduleKey", "file.rs")
      .setContents("fn main() {}")
      .build();
    context.fileSystem().add(inputFile);

    ClippyRunner clippyRunner = mock(ClippyRunner.class);
    when(clippyRunner.run(any(), any())).thenReturn(List.of(
      new ClippyDiagnostic(new ClippyMessage(
        new ClippyCode("clippy::absurd_extreme_comparisons"),
        "message",
        List.of(new ClippySpan("file.rs", 1, 2, 1, 4)))),
      // This issue should be ignored because "excluded.rs" is not part of FileSystem and thus not part of the analysis
      new ClippyDiagnostic(new ClippyMessage(
        new ClippyCode("clippy::absurd_extreme_comparisons"),
        "message",
        List.of(new ClippySpan("excluded.rs", 1, 2, 1, 4)))),
      // This issue should be ignored because there is no mapping to SonarQube rule
      new ClippyDiagnostic(new ClippyMessage(
        new ClippyCode("clippy::no_mapping"),
        "message",
        List.of(new ClippySpan("excluded.rs", 1, 2, 1, 4))))
    ));
    ClippySensor sensor = new ClippySensor(clippyRunner);
    sensor.execute(context);

    ArgumentCaptor<Path> pathCaptor = forClass(Path.class);
    @SuppressWarnings("unchecked") ArgumentCaptor<List<String>> lintsCaptor = forClass(List.class);
    verify(clippyRunner).run(pathCaptor.capture(), lintsCaptor.capture());
    assertThat(pathCaptor.getValue()).isEqualTo(baseDir);
    assertThat(lintsCaptor.getValue()).containsExactly("clippy::absurd_extreme_comparisons");

    assertThat(context.allIssues()).hasSize(1);
    Issue issue = context.allIssues().stream().findAny().get();
    assertThat(issue.ruleKey()).isEqualTo(RuleKey.of(RustLanguage.KEY, "S2198"));
    assertThat(issue.primaryLocation().message()).isEqualTo("message");
    assertThat(issue.primaryLocation().textRange().start().line()).isEqualTo(1);
    assertThat(issue.primaryLocation().textRange().start().lineOffset()).isEqualTo(1);
    assertThat(issue.primaryLocation().textRange().end().line()).isEqualTo(1);
    assertThat(issue.primaryLocation().textRange().end().lineOffset()).isEqualTo(3);
  }
}
