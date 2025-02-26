/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.clippy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClippyRunnerTest {

    // language=JSON
    private static final String CLIPPY_MESSAGE = """
      {
        "reason": "compiler-message",
        "package_id": "path+file:///Users/tibor/projects/saberduck/rust_hello#0.1.0",
        "manifest_path": "/Users/tibor/projects/saberduck/rust_hello/Cargo.toml",
        "target": {
          "kind": ["bin"],
          "crate_types": ["bin"],
          "name": "rust_hello",
          "src_path": "/Users/tibor/projects/saberduck/rust_hello/src/main.rs",
          "edition": "2024",
          "doc": true,
          "doctest": false,
          "test": true
        },
        "message": {
          "rendered": "warning: consider bringing this path into scope with the `use` keyword\\n --> src/foo.rs:4:13\\n  |\\n4 |     let x = std::f64::consts::PI;\\n  |             ^^^^^^^^^^^^^^^^^^^^\\n  |\\n  = help: for further information visit https://rust-lang.github.io/rust-clippy/master/index.html#absolute_paths\\n  = note: requested on the command line with `-W clippy::absolute-paths`\\n\\n",
          "$message_type": "diagnostic",
          "children": [
            {
              "children": [],
              "code": null,
              "level": "help",
              "message": "for further information visit https://rust-lang.github.io/rust-clippy/master/index.html#absolute_paths",
              "rendered": null,
              "spans": []
            },
            {
              "children": [],
              "code": null,
              "level": "note",
              "message": "requested on the command line with `-W clippy::absolute-paths`",
              "rendered": null,
              "spans": []
            }
          ],
          "code": { "code": "clippy::absolute_paths", "explanation": null },
          "level": "warning",
          "message": "consider bringing this path into scope with the `use` keyword",
          "spans": [
            {
              "byte_end": 51,
              "byte_start": 31,
              "column_end": 33,
              "column_start": 13,
              "expansion": null,
              "file_name": "src/foo.rs",
              "is_primary": true,
              "label": null,
              "line_end": 4,
              "line_start": 4,
              "suggested_replacement": null,
              "suggestion_applicability": null,
              "text": [
                {
                  "highlight_end": 33,
                  "highlight_start": 13,
                  "text": "    let x = std::f64::consts::PI;"
                }
              ]
            }
          ]
        }
      }
      """.replaceAll("\\r?\\n", "");

    @Test
    void test() throws Exception {
      ProcessWrapper processWrapper = mock(ProcessWrapper.class);
      when(processWrapper.getInputStream()).thenReturn(new ByteArrayInputStream(CLIPPY_MESSAGE.getBytes()));
      when(processWrapper.waitFor()).thenReturn(0);
      ClippyRunner clippyRunner = new ClippyRunner(processWrapper);
      var diagnostics = clippyRunner.run(Path.of("path/workdir"), List.of("clippy::some_lint"));
      verify(processWrapper).start(List.of("cargo", "clippy", "--quiet", "--message-format=json", "--", "-A", "clippy::all", "-Wclippy::some_lint"), Path.of("path/workdir"));
      assertThat(diagnostics).hasSize(1);
      assertThat(diagnostics.get(0).lintId()).isEqualTo("clippy::absolute_paths");
    }

    @Test
    void testExitValue() throws Exception {
      ProcessWrapper processWrapper = mock(ProcessWrapper.class);
      when(processWrapper.getInputStream()).thenReturn(new ByteArrayInputStream("".getBytes()));
      when(processWrapper.waitFor()).thenReturn(1);
      ClippyRunner clippyRunner = new ClippyRunner(processWrapper);
      assertThatThrownBy(() -> clippyRunner.run(Path.of("path/workdir"), List.of("clippy::some_lint")))
        .isInstanceOf(IllegalStateException.class).hasMessage("Clippy failed with exit code 1");
    }

    @Test
    void testIOException() throws Exception {
      ProcessWrapper processWrapper = mock(ProcessWrapper.class);
      doThrow(new IOException("error")).when(processWrapper).start(any(), any());
      ClippyRunner clippyRunner = new ClippyRunner(processWrapper);
      assertThatThrownBy(() -> clippyRunner.run(Path.of("path/workdir"), List.of("clippy::some_lint")))
        .isInstanceOf(IllegalStateException.class).hasMessage("Failed to run Clippy ");
    }

    @Test
    void testInterruptedException() throws Exception {
      ProcessWrapper processWrapper = mock(ProcessWrapper.class);
      when(processWrapper.getInputStream()).thenReturn(new ByteArrayInputStream(CLIPPY_MESSAGE.getBytes()));
      doThrow(new InterruptedException("error")).when(processWrapper).waitFor();
      ClippyRunner clippyRunner = new ClippyRunner(processWrapper);
      assertThatThrownBy(() -> clippyRunner.run(Path.of("path/workdir"), List.of("clippy::some_lint")))
        .isInstanceOf(IllegalStateException.class).hasMessage("Clippy was interrupted");
    }

}
