/*
 * SonarQube Rust Plugin
 * Copyright (C) 2025-2026 SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */
package org.sonarsource.rust.cobertura;

import org.sonarsource.rust.coverage.CoberturaSensor;
import org.sonarsource.rust.plugin.RustLanguage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.event.Level;
import org.sonar.api.batch.fs.internal.DefaultInputFile;
import org.sonar.api.batch.fs.internal.TestInputFileBuilder;
import org.sonar.api.batch.sensor.internal.DefaultSensorDescriptor;
import org.sonar.api.batch.sensor.internal.SensorContextTester;
import org.sonar.api.testfixtures.log.LogTesterJUnit5;

import static org.assertj.core.api.Assertions.assertThat;

class CoberturaSensorTest {

  @RegisterExtension
  LogTesterJUnit5 logTester = new LogTesterJUnit5().setLevel(Level.WARN);

  @TempDir
  Path baseDir;

  @Test
  void describe() {
    var sensor = new CoberturaSensor();
    var descriptor = new DefaultSensorDescriptor();
    sensor.describe(descriptor);

    assertThat(descriptor.name()).isEqualTo("Rust Cobertura Coverage");
    assertThat(descriptor.languages()).containsOnly(RustLanguage.KEY);
    assertThat(descriptor.configurationPredicate()).isNotNull();
  }


  @Test
  void execute() throws IOException {
    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty(CoberturaSensor.COBERTURA_REPORT_PATHS, "**/corbertura.xml");

    var xmlFile = baseDir.resolve("corbertura.xml");
    Files.copy(CoberturaParserTest.TEST_PROJECT_FILES_PATH.resolve("cobertura.xml"), xmlFile);

    var fs = context.fileSystem();
    DefaultInputFile absFile = TestInputFileBuilder.create("test", "src/abs.rs")
      .setLines(10)
      .build();

    fs.add(absFile);
    fs.add(TestInputFileBuilder.create("test", "src/sign.rs").setLines(10).build());
    fs.add(TestInputFileBuilder.create("test", "src/fizzbuzz.rs").setLines(20).build());
    fs.add(TestInputFileBuilder.create("test", "src/main.rs").setLines(40).build());

    var sensor = new CoberturaSensor();
    sensor.execute(context);

    assertThat(logTester.logs()).noneSatisfy(log -> assertThat(log).contains("problems"));

    assertThat(context.lineHits(absFile.key(), 1)).isEqualTo(1);
    assertThat(context.lineHits(absFile.key(), 2)).isEqualTo(1);
    assertThat(context.lineHits(absFile.key(), 3)).isEqualTo(1);
    assertThat(context.lineHits(absFile.key(), 5)).isEqualTo(0);
    assertThat(context.lineHits(absFile.key(), 7)).isEqualTo(1);

    assertThat(context.conditions(absFile.key(), 2)).isEqualTo(2);
    assertThat(context.coveredConditions(absFile.key(), 2)).isEqualTo(1);
  }

  @Test
  void execute_no_reports() {
    var context = SensorContextTester.create(baseDir);
    var sensor = new CoberturaSensor();
    sensor.execute(context);

    assertThat(logTester.logs()).contains("No Cobertura report files found");
  }

  @Test
  void execute_problems() throws IOException {
    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty(CoberturaSensor.COBERTURA_REPORT_PATHS, "**/corbertura.xml");

    var xmlFile = baseDir.resolve("corbertura.xml");
    Files.writeString(xmlFile, """
    <?xml version="1.0" ?>
      <!DOCTYPE coverage SYSTEM "https://cobertura.sourceforge.net/xml/coverage-04.dtd">
      <coverage>
        <sources>
          <source>/Users/gyulasallai/projects/sandbox/rust-tests</source>
        </sources>
        <packages>
          <package>
            <classes>
              <class name="abs" filename="src/abs.rs">
                <lines>
                  <line number="1" />
                  <line hits="1" />
                  <line number="2" hits="1" />
                </lines>
              </class>
            </classes>
          </package>
        </packages>
      </coverage>
    """);

    context.fileSystem().add(TestInputFileBuilder.create("test", "src/abs.rs").setLines(10).build());

    var sensor = new CoberturaSensor();
    sensor.execute(context);

    assertThat(logTester.logs(Level.WARN)).contains("Found 2 problems in Cobertura report: %s. More details in verbose mode".formatted(xmlFile.toString()));
  }

  @Test
  void execute_invalid_xml() throws IOException {
    var context = SensorContextTester.create(baseDir);
    context.settings().setProperty(CoberturaSensor.COBERTURA_REPORT_PATHS, "**/corbertura.xml");

    var xmlFile = baseDir.resolve("corbertura.xml");
    Files.writeString(xmlFile, """
    <coverage>
      <packages>
        <package><classes></classes></package>
    """);

    var sensor = new CoberturaSensor();
    sensor.execute(context);

    assertThat(logTester.logs(Level.ERROR)).anySatisfy(msg ->
      assertThat(msg).contains("Failed to parse Cobertura report"));
  }
}
