from pathlib import Path

path = Path("analyzer/src/visitors/cpd.rs")
text = path.read_text()
old = '''    fn enter_node(&mut self, node: Node<'_>) -> Result<(), AnalyzerError> {
        if is_cfg_test_attribute(node, self.source_code) {
            // Ignore everything under '#[cfg(test)]' nodes as we do not want CPD on test code.
            // In the grammar, the attribute is not attached to the tree it applies to, rather it's a sibling node, so we'll look for the next sibling
            // and attach its effects there.
            if let Some(sibling) = node.next_named_sibling() {
                self.test_code_node = Some(sibling.id());
                return Ok(());
            }
        }

        if node.child_count() == 0 && self.test_code_node.is_none() {
            // Ignore source files
            // We wrongly consider them as tokens when they denote empty files
            if node.kind() == "source_file" {
                return Ok(());
            }

            // Ignore missing nodes
            // They denote syntax errors and can have identical starting and ending columns
            if node.is_missing() {
                return Ok(());
            }

            // Ignore error nodes
            // They denote syntax errors and can be unpredictable
            if node.is_error() {
                return Ok(());
            }

            // Number-like tokens
            if node.kind() == "integer_literal" || node.kind() == "float_literal" {
                self.new_token("NUMBER", node);
                return Ok(());
            }

            // String-like tokens
            if node.kind() == "string_content" {
                if let Some(parent) = node
                    .parent()
                    .filter(|parent| parent.kind() == "raw_string_literal")
                {
                    self.new_token("STRING", parent);
                } else {
                    self.new_token("STRING", node);
                }
                return Ok(());
            }

            // Default case
            let image = &self.source_code[node.start_byte()..node.end_byte()];
            self.new_token(image, node);
        }
        Ok(())
    }
'''
new = '''    fn enter_node(&mut self, node: Node<'_>) -> Result<(), AnalyzerError> {
        if is_cfg_test_attribute(node, self.source_code) {
            // Ignore everything under '#[cfg(test)]' nodes as we do not want CPD on test code.
            // In the grammar, the attribute is not attached to the tree it applies to, rather it's a sibling node, so we'll look for the next sibling
            // and attach its effects there.
            if let Some(sibling) = node.next_named_sibling() {
                self.test_code_node = Some(sibling.id());
                return Ok(());
            }
        }

        if node.child_count() == 0 && self.test_code_node.is_none() {
            self.new_leaf_token(node);
        }
        Ok(())
    }

    fn new_leaf_token(&mut self, node: Node<'_>) {
        match node.kind() {
            "source_file" => {}
            _ if node.is_missing() => {}
            _ if node.is_error() => {}
            "integer_literal" | "float_literal" => self.new_token("NUMBER", node),
            "string_content" => self.new_string_token(node),
            _ => {
                let image = &self.source_code[node.start_byte()..node.end_byte()];
                self.new_token(image, node);
            }
        }
    }

    fn new_string_token(&mut self, node: Node<'_>) {
        let token_node = node
            .parent()
            .filter(|parent| parent.kind() == "raw_string_literal")
            .unwrap_or(node);
        self.new_token("STRING", token_node);
    }
'''
if old not in text:
    raise SystemExit("target block not found")
path.write_text(text.replace(old, new, 1))
