/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.analysis;

import com.sonarsource.rust.plugin.Analyzer;
import com.sonarsource.rust.plugin.AnalyzerFactory;
import com.sonarsource.rust.plugin.RustLanguage;
import com.sonarsource.rust.plugin.RustSensor;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.event.Level;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.internal.TestInputFileBuilder;
import org.sonar.api.batch.sensor.highlighting.TypeOfText;
import org.sonar.api.batch.sensor.internal.DefaultSensorDescriptor;
import org.sonar.api.batch.sensor.internal.SensorContextTester;
import org.sonar.api.testfixtures.log.LogTesterJUnit5;

import static org.assertj.core.api.Assertions.assertThat;

class RustSensorTest {

  public static final String PROJECT_KEY = "moduleKey";

  @RegisterExtension
  protected LogTesterJUnit5 logTester = new LogTesterJUnit5().setLevel(Level.DEBUG);

  @TempDir
  protected File baseDir;
  protected SensorContextTester context;

  @BeforeEach
  void setup() {
    context = SensorContextTester.create(baseDir);
  }

  @Test
  void sensor_descriptor() {
    DefaultSensorDescriptor descriptor = new DefaultSensorDescriptor();
    sensor().describe(descriptor);

    assertThat(descriptor.name()).isEqualTo("Rust sensor");
    assertThat(descriptor.languages()).containsExactly("rust");
  }

  @Test
  void analyze_file() {
    RustSensor sensor = sensor();
    context.fileSystem().add(inputFile("test.rs", "fn main() {}"));
    sensor.execute(context);
    var fnKeyword = context.highlightingTypeAt("%s:test.rs".formatted(PROJECT_KEY), 1, 0);
    assertThat(fnKeyword)
      .containsExactly(TypeOfText.KEYWORD);
  }

  private InputFile inputFile(String relativePath, String content) {
    return new TestInputFileBuilder(PROJECT_KEY, relativePath)
      .setModuleBaseDir(baseDir.toPath())
      .setType(InputFile.Type.MAIN)
      .setLanguage(RustLanguage.KEY)
      .setCharset(StandardCharsets.UTF_8)
      .setContents(content)
      .build();
  }

  private RustSensor sensor() {
    return new RustSensor(new AnalyzerFactory(null) {
      @Override
      public Analyzer create() {
        return new Analyzer(AnalyzerTest.RUN_LOCAL_ANALYZER_COMMAND);
      }
    });
  }

}
