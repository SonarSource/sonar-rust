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
package org.sonarsource.rust.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.utils.PathUtils;

/**
 * This FileLocator class was introduced as part of the implementation of LCOV coverage reports in the Sonar Rust plugin.
 * It is responsible for locating files based on their paths using a reverse path tree to index and retrieve files efficiently.
 * The logic for this class was adapted from the JavaScript analyzer, which uses similar logic for parsing LCOV files.
 *
 * @see https://github.com/SonarSource/SonarJS/blob/master/sonar-plugin/sonar-javascript-plugin/src/main/java/org/sonar/plugins/javascript/lcov/FileLocator.java
 */
public class FileLocator {

  private static class ReversePathTree {

    private Node root = new Node();

    void index(InputFile inputFile, String[] path) {
      Node currentNode = root;
      for (int i = path.length - 1; i >= 0; i--) {
        currentNode = currentNode.children.computeIfAbsent(path[i], e -> new Node());
      }
      currentNode.file = inputFile;
    }

    InputFile getFileWithSuffix(String[] path) {
      Node currentNode = root;

      for (int i = path.length - 1; i >= 0; i--) {
        currentNode = currentNode.children.get(path[i]);
        if (currentNode == null) {
          return null;
        }
      }
      return getFirstLeaf(currentNode);
    }

    private static InputFile getFirstLeaf(Node node) {
      while (!node.children.isEmpty()) {
        node = node.children.values().iterator().next();
      }
      return node.file;
    }

    static class Node {

      final Map<String, Node> children = new LinkedHashMap<>();
      InputFile file = null;
    }
  }

  private final ReversePathTree tree = new ReversePathTree();

  @SuppressWarnings("deprecation")
  public FileLocator(Iterable<InputFile> inputFiles) {
    inputFiles.forEach(inputFile -> {
      String[] path = inputFile.relativePath().split("/");
      tree.index(inputFile, path);
    });
  }

  public InputFile getInputFile(String filePath) {
    String sanitizedPath = PathUtils.sanitize(filePath);
    if (sanitizedPath == null) {
      return null;
    }
    String[] pathElements = sanitizedPath.split("/");
    return tree.getFileWithSuffix(pathElements);
  }
}
