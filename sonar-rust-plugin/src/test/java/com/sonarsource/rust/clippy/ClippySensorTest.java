/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.clippy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sonarsource.rust.cargo.CargoManifestProvider;
import com.sonarsource.rust.plugin.RustLanguage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.event.Level;
import org.sonar.api.batch.fs.internal.TestInputFileBuilder;
import org.sonar.api.batch.rule.ActiveRules;
import org.sonar.api.batch.rule.internal.ActiveRulesBuilder;
import org.sonar.api.batch.rule.internal.NewActiveRule;
import org.sonar.api.batch.sensor.internal.DefaultSensorDescriptor;
import org.sonar.api.batch.sensor.internal.SensorContextTester;
import org.sonar.api.batch.sensor.issue.Issue;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.testfixtures.log.LogTesterJUnit5;

class ClippySensorTest {

  @RegisterExtension
  LogTesterJUnit5 logTester = new LogTesterJUnit5().setLevel(Level.DEBUG);

  @TempDir
  Path baseDir;

  @Test
  void testDescribe() {
    var sensor = new ClippySensor();
    var descriptor = new DefaultSensorDescriptor();
    sensor.describe(descriptor);

    assertThat(descriptor.name()).isEqualTo("Clippy");
    assertThat(descriptor.languages()).containsOnly(RustLanguage.KEY);
  }

  @Test
  void executeSkipsIfDisabled() {
    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty(ClippySensor.CLIPPY_SENSOR_ENABLED, "false");

    var clippyPrerequisite = mock(ClippyPrerequisite.class);
    var clippyRunner = mock(ClippyRunner.class);
    var sensor = new ClippySensor(clippyPrerequisite, clippyRunner);
    sensor.execute(context);

    assertThat(logTester.logs()).contains("Clippy sensor is disabled");
  }

  @Test
  void executeSkipsIfNoManifest() {
    var context = SensorContextTester.create(baseDir);

    var clippyPrerequisite = mock(ClippyPrerequisite.class);
    var clippyRunner = mock(ClippyRunner.class);
    var sensor = new ClippySensor(clippyPrerequisite, clippyRunner);
    sensor.execute(context);

    assertThat(logTester.logs()).contains("No Cargo manifest found, skipping Clippy analysis");
  }

  @Test
  void executeSkipsIfPrerequisiteFails() throws IOException {
    var context = SensorContextTester.create(baseDir);
    var manifest = baseDir.resolve("Cargo.toml");
    Files.createFile(manifest);

    var clippyPrerequisite = mock(ClippyPrerequisite.class);
    doThrow(new IllegalStateException("error")).when(clippyPrerequisite).check(any());

    var clippyRunner = mock(ClippyRunner.class);
    var sensor = new ClippySensor(clippyPrerequisite, clippyRunner);
    sensor.execute(context);

    assertThat(logTester.logs()).contains("Failed to check Clippy prerequisites");
  }

  @Test
  void executeFailsIfClippyFails() throws IOException {
    var context = SensorContextTester.create(baseDir);
    var manifest = baseDir.resolve("Cargo.toml");
    Files.createFile(manifest);

    var clippyPrerequisite = mock(ClippyPrerequisite.class);
    doNothing().when(clippyPrerequisite).check(any());

    var clippyRunner = mock(ClippyRunner.class);
    when(clippyRunner.run(any(), any())).thenThrow(new IllegalStateException("error"));

    var sensor = new ClippySensor(clippyPrerequisite, clippyRunner);
    sensor.execute(context);

    assertThat(logTester.logs()).contains("Failed to run Clippy");
  }

  @Test
  void executeRunsClippyAndSavesIssues() throws IOException {
    var context = SensorContextTester.create(baseDir);
    ActiveRules activeRules = new ActiveRulesBuilder()
      .addRule(new NewActiveRule.Builder().setRuleKey(RuleKey.of(RustLanguage.KEY, "S2198")).build())
      .build();
    context.setActiveRules(activeRules);
    var inputFile = new TestInputFileBuilder("moduleKey", "file.rs")
      .setContents("fn main() {}")
      .build();
    context.fileSystem().add(inputFile);

    var manifest = baseDir.resolve("Cargo.toml");
    Files.createFile(manifest);

    ClippyPrerequisite clippyPrerequisite = mock(ClippyPrerequisite.class);
    doNothing().when(clippyPrerequisite).check(any());

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
    ClippySensor sensor = new ClippySensor(clippyPrerequisite, clippyRunner);
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

  @Test
  void executeRunsWithAdjustedDiagnosticLocation() throws Exception {
    var context = SensorContextTester.create(baseDir);
    var rules = new ActiveRulesBuilder()
      .addRule(new NewActiveRule.Builder().setRuleKey(RuleKey.of(RustLanguage.KEY, "S2198")).build())
      .build();
    context.setActiveRules(rules);

    var subDir = baseDir.resolve("subdir");
    Files.createDirectories(subDir);

    var manifest = Files.createFile(subDir.resolve("Cargo.toml"));
    context.settings().setProperty(CargoManifestProvider.CARGO_MANIFEST_PATHS, manifest.toString());

    var srcDir = subDir.resolve("src");
    Files.createDirectories(srcDir);

    var inputFile = new TestInputFileBuilder("moduleKey", "subdir/src/file.rs")
      .setContents("fn main() { if 100 > i32::MAX {} }")
      .build();
    context.fileSystem().add(inputFile);

    var clippyPrerequisite = mock(ClippyPrerequisite.class);
    doNothing().when(clippyPrerequisite).check(any());

    var clippyRunner = mock(ClippyRunner.class);
    when(clippyRunner.run(any(), any())).thenReturn(List.of(
      new ClippyDiagnostic(new ClippyMessage(
        new ClippyCode("clippy::absurd_extreme_comparisons"),
        "Unnecessary mathematical comparisons should not be made",
        // Note that the filename is relative to the Cargo.toml directory, not the project base directory
        List.of(new ClippySpan("src/file.rs", 1, 16, 1, 30)))
    )));

    var sensor = new ClippySensor(clippyPrerequisite, clippyRunner);
    sensor.execute(context);

    assertThat(context.allIssues()).hasSize(1);

    var issue = context.allIssues().stream().findAny().get();
    assertThat(issue.primaryLocation().inputComponent().key()).isEqualTo("moduleKey:subdir/src/file.rs");
    assertThat(issue.primaryLocation().textRange().start().line()).isEqualTo(1);
  }
}
