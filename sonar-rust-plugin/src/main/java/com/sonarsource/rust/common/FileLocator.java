/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.utils.PathUtils;

/**
 * Copied from the Sonar JavaScript analyzer.
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
