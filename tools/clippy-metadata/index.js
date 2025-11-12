/*
 * SonarQube Rust Plugin
 * Copyright (C) 2025 SonarSource Sàrl
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
const fs = require('fs');
const path = require('path');
const { Command } = require('commander');
const Parser = require('tree-sitter');
const Rust = require('tree-sitter-rust');

const CLIPPY_RULE_URL = 'https://rust-lang.github.io/rust-clippy/master/index.html';
const CATEGORY_OFFSET = 3;

/**
 * Parse command line arguments
 */
const program = new Command();
program
  .version('1.0.0')
  .description('Generate JSON rule metadata from Clippy lints.')
  .argument('<clippy-lints-path>', 'Path to clippy lints source directory')
  .argument('<output-file>', 'Output file to save Clippy metadata')
  .parse(process.argv);

const [clippyLintPath, outputFile] = program.args;

/**
 * Collect Clippy lint files
 */
console.log(`Collecting Clippy lints from ${clippyLintPath}`);

const clippyLintFiles = fs
  .readdirSync(clippyLintPath, { withFileTypes: true, recursive: true })
  .filter((dirent) => dirent.isFile() && dirent.name.endsWith('.rs'));

console.log(`Found ${clippyLintFiles.length} clippy lint files`);

/**
 * Configure Tree-sitter to parse Rust code
 */
const parser = new Parser();
parser.setLanguage(Rust);

/**
 * Collect lints from Clippy lint files
 */
const lints = [];
for (const lintFile of clippyLintFiles) {
  const filePath = path.join(lintFile.parentPath, lintFile.name);

  console.log(`Processing ${filePath}`);

  /**
   * Parse Clippy lint file
   */
  const sourceCode = fs.readFileSync(filePath, 'utf8');
  const tree = parser.parse(sourceCode);
  const root = tree.rootNode;

  /**
   * Collect lints from Clippy lint file
   */
  const foundLints = [];
  collectLints(root, foundLints);

  console.log(`Found ${foundLints.length} lints in ${filePath}`);

  lints.push(...foundLints);
}

console.log(`Found ${lints.length} lints`);

/**
 * Save metadata to output file
 */
const metadata = JSON.stringify(lints, null, 2);
fs.writeFileSync(outputFile, metadata);

console.log(`Saved metadata to ${outputFile}`);

/////////////////////////// Helper Functions ///////////////////////////

/**
 * Collect lints from Clippy lint file by traversing the AST
 * 
 * A Clippy lint is declared using the `declare_clippy_lint` macro.
 */
function collectLints(node, lints) {
  /**
   * Check if the node is a `declare_clippy_lint` macro invocation
   */
  if (node.type === 'macro_invocation') {
    const macroName = node.macroNode.text;
    if (macroName === 'declare_clippy_lint') {
      const lint = parseLint(node);
      if (lint) {
        lints.push(lint);
      }
      return;
    }
  }

  /**
   * Otherwise, recursively traverse the AST
   */
  for (let i = 0; i < node.childCount; i++) {
    collectLints(node.child(i), lints);
  }
}

/**
 * Parse a Clippy lint from a `declare_clippy_lint` macro invocation
 * 
 * The `declare_clippy_lint` macro invocation has the following structure:
 * `declare_clippy_lint! '{'`
 *  <description>
 *  'pub' <name> ','
 *  <category> ','
 *  <message>
 * '}'`
 */
function parseLint(macro) {
  const tokenTree = macro.child(2);
  if (tokenTree.type !== 'token_tree') {
    console.log(`Error: Unexpected token tree type: ${tokenTree.type}`);
    return null;
  }

  let pubTokenIdx = -1;
  let tokenIdx = 0;
  while (tokenIdx < tokenTree.childCount) {
    const token = tokenTree.children[tokenIdx];
    if (token.text === 'pub') {
      pubTokenIdx = tokenIdx;
      break;
    }
    tokenIdx++;
  }

  if (pubTokenIdx === -1) {
    console.log('Error: Could not find `pub` token in Clippy lint declaration');
    return null;
  }

  const category = tokenTree.children[pubTokenIdx + CATEGORY_OFFSET].text;
  if (category === 'internal') {
    return null;
  }

  const key = tokenTree.children[pubTokenIdx + 1].text.toLowerCase();
  const url = `${CLIPPY_RULE_URL}#${key}`;
  const description = `Clippy lint <code>${key}</code>.`;

  return { key, name: key, url, description };
}
