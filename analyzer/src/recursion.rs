/*
 * SonarQube Rust Plugin
 * Copyright (C) SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * You can redistribute and/or modify this program under the terms of
 * the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */
//! Symbol resolution and recursion detection over file or project syntax trees.
//!
//! Only uniquely resolved calls become graph edges. This is a syntax-backed
//! resolver, not rustc: macro expansion, generic substitution,
//! user-defined autoderef and dynamic dispatch require a project semantic model.
use crate::tree::{NodeIterator, TreeSitterLocation};
use std::cell::RefCell;
use std::collections::{HashMap, HashSet};
use tree_sitter::Node;

const MAX_RESOLUTION_DEPTH: usize = 32;

/// Names of synthetic crate roots and their Cargo dependency bindings.
#[derive(Default, Clone)]
pub struct CrateContext {
    pub dependencies: HashMap<String, String>,
    pub edition: u16,
}

#[derive(Default)]
pub struct Recursion {
    /// The first call into the same strongly connected component, per function.
    #[cfg(test)]
    locations: HashMap<usize, TreeSitterLocation>,
    calls: HashMap<usize, TreeSitterLocation>,
}

impl Recursion {
    pub fn new(root: Node<'_>, source: &str) -> Self {
        Self::in_project(root, source, &HashMap::new())
    }

    pub fn in_project(
        root: Node<'_>,
        source: &str,
        crates: &HashMap<String, CrateContext>,
    ) -> Self {
        Self::with_context(root, source, crates, true, None)
    }

    pub fn reachable_from(
        root: Node<'_>,
        source: &str,
        crates: &HashMap<String, CrateContext>,
        workspace_ranges: &[(usize, usize)],
    ) -> Self {
        Self::with_context(root, source, crates, true, Some(workspace_ranges))
    }

    pub fn unknown_root(root: Node<'_>, source: &str) -> Self {
        Self::with_context(root, source, &HashMap::new(), false, None)
    }

    fn with_context(
        root: Node<'_>,
        source: &str,
        crates: &HashMap<String, CrateContext>,
        known_root: bool,
        workspace_ranges: Option<&[(usize, usize)]>,
    ) -> Self {
        let resolver = Resolver::with_crates(root, source, crates, known_root);
        let mut edges = vec![Vec::new(); resolver.functions.len()];
        let mut calls = vec![Vec::new(); resolver.functions.len()];
        for call in nodes(root).filter(|node| node.kind() == "call_expression") {
            let Some(caller) = enclosing_function(call) else {
                continue;
            };
            let Some(&from) = resolver.function_ids.get(&caller.id()) else {
                continue;
            };
            calls[from].push(call);
        }
        // A cycle affecting a workspace function is entirely reachable from
        // that function. Keep dependency symbols available, but resolve their
        // bodies only when a workspace call actually reaches them.
        let mut pending: Vec<_> = resolver
            .functions
            .iter()
            .enumerate()
            .filter(|(_, f)| {
                workspace_ranges.is_none_or(|ranges| {
                    ranges.iter().any(|&(start, end)| {
                        start <= f.node.start_byte() && f.node.end_byte() <= end
                    })
                })
            })
            .map(|(index, _)| index)
            .collect();
        let mut visited = vec![false; resolver.functions.len()];
        while let Some(from) = pending.pop() {
            if std::mem::replace(&mut visited[from], true) {
                continue;
            }
            for &call in &calls[from] {
                if let Some((to, location)) = resolver.resolve_call(call, 0) {
                    edges[from].push((to, location));
                    if !visited[to] {
                        pending.push(to);
                    }
                }
            }
        }
        let components = strongly_connected_components(&edges);
        #[cfg(test)]
        let mut locations = HashMap::new();
        let mut recursive_calls = HashMap::new();
        for (from, calls) in edges.iter().enumerate() {
            if let Some((_, location)) = calls
                .iter()
                .find(|(to, _)| components[from] == components[*to])
            {
                let location_range = TreeSitterLocation::from_tree_sitter_node(*location);
                #[cfg(test)]
                locations.insert(resolver.functions[from].node.id(), location_range.clone());
                recursive_calls.insert(location.id(), location_range);
            }
        }
        Self {
            #[cfg(test)]
            locations,
            calls: recursive_calls,
        }
    }

    pub fn ranges(&self) -> impl Iterator<Item = (usize, usize)> + '_ {
        self.calls
            .values()
            .map(|location| (location.start_byte, location.end_byte))
    }

    pub fn for_file(root: Node<'_>, ranges: &HashSet<(usize, usize)>) -> Self {
        // A shared source can be indexed in several Cargo targets with different
        // cycle edges. Keep the once-per-function score when merging their ranges.
        let mut functions = HashSet::new();
        let calls = nodes(root)
            .filter(|node| ranges.contains(&(node.start_byte(), node.end_byte())))
            .filter(|node| {
                enclosing_function(*node).is_some_and(|function| functions.insert(function.id()))
            })
            .map(|node| (node.id(), TreeSitterLocation::from_tree_sitter_node(node)))
            .collect();
        Self {
            calls,
            #[cfg(test)]
            locations: HashMap::new(),
        }
    }

    pub fn call_location(&self, callee: Node<'_>) -> Option<&TreeSitterLocation> {
        self.calls.get(&callee.id())
    }

    #[cfg(test)]
    pub fn location(&self, function: Node<'_>) -> Option<&TreeSitterLocation> {
        self.locations.get(&function.id())
    }
}

// Iterative Kosaraju: linear time and no recursion on the Rust process stack.
fn strongly_connected_components(edges: &[Vec<(usize, Node<'_>)>]) -> Vec<usize> {
    let mut seen = vec![false; edges.len()];
    let mut order = Vec::new();
    let mut reverse = vec![Vec::new(); edges.len()];
    for (from, calls) in edges.iter().enumerate() {
        for (to, _) in calls {
            reverse[*to].push(from);
        }
        if seen[from] {
            continue;
        }
        seen[from] = true;
        let mut stack = vec![(from, 0)];
        while let Some((node, next)) = stack.last_mut() {
            if let Some((to, _)) = edges[*node].get(*next) {
                *next += 1;
                if !seen[*to] {
                    seen[*to] = true;
                    stack.push((*to, 0));
                }
            } else {
                order.push(*node);
                stack.pop();
            }
        }
    }
    let mut components = vec![usize::MAX; edges.len()];
    for node in order.into_iter().rev() {
        if components[node] != usize::MAX {
            continue;
        }
        components[node] = node;
        let mut stack = vec![node];
        while let Some(from) = stack.pop() {
            for &to in &reverse[from] {
                if components[to] == usize::MAX {
                    components[to] = node;
                    stack.push(to);
                }
            }
        }
    }
    components
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Symbol<'tree> {
    Function(usize),
    Type(Node<'tree>),
    Alias(Node<'tree>),
    Module(Node<'tree>),
    Trait(Node<'tree>),
    Binding(Node<'tree>),
    Unknown,
}

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
enum Namespace {
    Value,
    Type,
}

#[derive(Default)]
struct ImportCandidates<'tree> {
    explicit: Vec<Symbol<'tree>>,
    glob: Vec<Symbol<'tree>>,
    glob_unknown: bool,
}

impl<'tree> ImportCandidates<'tree> {
    fn resolve(self) -> Option<Symbol<'tree>> {
        let candidates = if !self.explicit.is_empty() {
            self.explicit
        } else if self.glob_unknown {
            return Some(Symbol::Unknown);
        } else {
            self.glob
        };
        let mut unique = Vec::new();
        for symbol in candidates {
            if !unique.contains(&symbol) {
                unique.push(symbol);
            }
        }
        match unique.len() {
            0 => None,
            1 => unique.pop(),
            _ => Some(Symbol::Unknown),
        }
    }
}

struct Function<'tree> {
    node: Node<'tree>,
    owner: Option<Node<'tree>>,
    trait_id: Option<usize>,
    implementation: Option<Node<'tree>>,
    method: bool,
}

struct Resolver<'tree, 'source> {
    source: &'source str,
    root: Node<'tree>,
    functions: Vec<Function<'tree>>,
    function_ids: HashMap<usize, usize>,
    symbols: HashMap<(usize, String), Vec<Symbol<'tree>>>,
    import_items: HashMap<usize, Vec<Node<'tree>>>,
    implementations: Vec<Node<'tree>>,
    implementation_types: RefCell<HashMap<(usize, usize), Option<Node<'tree>>>>,
    implementation_traits: RefCell<HashMap<(usize, usize), Option<Symbol<'tree>>>>,
    active_lookups: RefCell<HashSet<(usize, String, Namespace)>>,
    crate_roots: HashSet<usize>,
    known_root: bool,
    extern_preludes: HashMap<usize, HashMap<String, Node<'tree>>>,
    editions: HashMap<usize, u16>,
}

impl<'tree, 'source> Resolver<'tree, 'source> {
    fn with_crates(
        root: Node<'tree>,
        source: &'source str,
        crates: &HashMap<String, CrateContext>,
        known_root: bool,
    ) -> Self {
        let mut resolver = Self {
            source,
            root,
            functions: Vec::new(),
            function_ids: HashMap::new(),
            symbols: HashMap::new(),
            import_items: HashMap::new(),
            implementations: Vec::new(),
            implementation_types: RefCell::new(HashMap::new()),
            implementation_traits: RefCell::new(HashMap::new()),
            active_lookups: RefCell::new(HashSet::new()),
            crate_roots: HashSet::new(),
            known_root,
            extern_preludes: HashMap::new(),
            editions: HashMap::new(),
        };
        let roots: HashMap<_, _> = children(root)
            .filter(|n| n.kind() == "mod_item")
            .filter_map(|n| {
                n.child_by_field_name("name")
                    .map(|name| (resolver.text(name).to_owned(), n))
            })
            .collect();
        for (name, context) in crates {
            let Some(&node) = roots.get(name) else {
                continue;
            };
            resolver.crate_roots.insert(node.id());
            resolver.editions.insert(node.id(), context.edition);
            resolver.extern_preludes.insert(
                node.id(),
                context
                    .dependencies
                    .iter()
                    .filter_map(|(alias, target)| {
                        roots.get(target).map(|&root| (alias.clone(), root))
                    })
                    .collect(),
            );
        }
        let mut all_nodes: Vec<_> = nodes(root).collect();
        all_nodes.sort_by_key(|node| node.start_byte());
        for &node in &all_nodes {
            let Some(parent) = node.parent() else {
                continue;
            };
            if matches!(
                node.kind(),
                "use_declaration"
                    | "extern_crate_declaration"
                    | "macro_invocation"
                    | "foreign_mod_item"
            ) || (node.kind() == "expression_statement"
                && children(node)
                    .next()
                    .is_some_and(|child| child.kind() == "macro_invocation"))
            {
                resolver
                    .import_items
                    .entry(parent.id())
                    .or_default()
                    .push(node);
            }
            let symbol = match node.kind() {
                "mod_item" => Some(Symbol::Module(node)),
                "struct_item" | "enum_item" | "union_item" => Some(Symbol::Type(node)),
                "trait_item" => Some(Symbol::Trait(node)),
                "type_item" => Some(Symbol::Alias(node)),
                "const_item" | "static_item" => Some(Symbol::Binding(node)),
                "function_signature_item" => Some(Symbol::Unknown),
                "impl_item" => {
                    resolver.implementations.push(node);
                    None
                }
                "function_item" => {
                    let implementation = parent
                        .parent()
                        .filter(|owner| matches!(owner.kind(), "impl_item" | "trait_item"));
                    let index = resolver.functions.len();
                    let method = node
                        .child_by_field_name("parameters")
                        .is_some_and(|parameters| {
                            children(parameters).any(|p| {
                                p.kind() == "self_parameter"
                                    || p.child_by_field_name("pattern")
                                        .is_some_and(|p| p.kind() == "self")
                            })
                        });
                    resolver.functions.push(Function {
                        node,
                        owner: None,
                        trait_id: None,
                        implementation,
                        method,
                    });
                    resolver.function_ids.insert(node.id(), index);
                    if implementation.is_none() {
                        Some(Symbol::Function(index))
                    } else {
                        None
                    }
                }
                _ => None,
            };
            if let (Some(symbol), Some(name)) = (symbol, node.child_by_field_name("name")) {
                resolver
                    .symbols
                    .entry((parent.id(), resolver.text(name).to_owned()))
                    .or_default()
                    .push(symbol);
            }
        }
        // All item names must exist before resolving impl owners and aliases.
        for index in 0..resolver.functions.len() {
            let Some(implementation) = resolver.functions[index].implementation else {
                continue;
            };
            if implementation.kind() == "impl_item" {
                let owner = implementation
                    .child_by_field_name("type")
                    .and_then(|ty| resolver.resolve_type(ty, implementation, 0));
                let trait_id = implementation.child_by_field_name("trait").and_then(|ty| {
                    match resolver.resolve_path(ty, implementation, Namespace::Type, 0) {
                        Some(Symbol::Trait(node)) => Some(node.id()),
                        _ => None,
                    }
                });
                resolver.functions[index].owner = owner;
                resolver.functions[index].trait_id = trait_id;
            } else {
                resolver.functions[index].trait_id = Some(implementation.id());
            }
        }
        resolver
    }

    fn crate_root(&self, context: Node<'tree>) -> Node<'tree> {
        let mut node = context;
        loop {
            if self.crate_roots.contains(&node.id()) {
                return node;
            }
            let Some(parent) = node.parent() else {
                return self.root;
            };
            node = parent;
        }
    }

    fn confirmed_crate_root(&self, context: Node<'tree>) -> Option<Node<'tree>> {
        if self.known_root || !self.crate_roots.is_empty() {
            Some(self.crate_root(context))
        } else {
            None
        }
    }

    fn external(&self, name: &str, context: Node<'tree>) -> Option<Symbol<'tree>> {
        self.extern_preludes
            .get(&self.crate_root(context).id())?
            .get(name)
            .copied()
            .map(Symbol::Module)
    }

    fn legacy_edition(&self, context: Node<'tree>) -> bool {
        self.editions
            .get(&self.crate_root(context).id())
            .is_some_and(|edition| *edition < 2018)
    }

    fn text(&self, node: Node<'_>) -> &str {
        self.source[node.byte_range()].trim_start_matches("r#")
    }

    fn resolve_call(&self, call: Node<'tree>, depth: usize) -> Option<(usize, Node<'tree>)> {
        if depth > MAX_RESOLUTION_DEPTH {
            return None;
        }
        let mut callee = call.child_by_field_name("function")?;
        if callee.kind() == "generic_function" {
            callee = callee.child_by_field_name("function")?;
        }
        if callee.kind() == "field_expression" {
            let receiver = callee.child_by_field_name("value")?;
            let field = callee.child_by_field_name("field")?;
            if receiver.kind() == "self" {
                if let Some(function) = enclosing_function(call)
                    .and_then(|node| self.function_ids.get(&node.id()).copied())
                {
                    if self.functions[function]
                        .implementation
                        .is_some_and(|i| i.kind() == "trait_item")
                    {
                        return self
                            .default_trait_call(function, self.text(field), call, depth + 1)
                            .map(|target| (target, field));
                    }
                }
            }
            let ty = self.expression_type(receiver, call, depth + 1)?;
            let function = self.member(
                ty,
                self.text(field),
                None,
                Some(self.expression_mode(receiver, call, depth + 1)?),
                call,
                depth + 1,
            )?;
            return Some((function, field));
        }
        if callee.kind() == "parenthesized_expression" {
            callee = children(callee).next()?;
        }
        // Trait::method(receiver) is UFCS: its concrete receiver selects the impl.
        if callee.kind() == "scoped_identifier" {
            if let Some(prefix) = callee.child_by_field_name("path") {
                if let Some(Symbol::Trait(trait_node)) =
                    self.resolve_path(prefix, call, Namespace::Type, depth + 1)
                {
                    let arguments = call.child_by_field_name("arguments")?;
                    let receiver = children(arguments).next()?;
                    let ty = self.expression_type(receiver, call, depth + 1)?;
                    let name = callee.child_by_field_name("name")?;
                    return self
                        .member(
                            ty,
                            self.text(name),
                            Some(trait_node.id()),
                            None,
                            call,
                            depth + 1,
                        )
                        .map(|index| (index, name));
                }
            }
        }
        let symbol = self.resolve_path(callee, call, Namespace::Value, depth + 1)?;
        let function = self.callable(symbol, depth + 1)?;
        let location = callee.child_by_field_name("name").unwrap_or(callee);
        Some((function, location))
    }

    fn callable(&self, symbol: Symbol<'tree>, depth: usize) -> Option<usize> {
        if depth > MAX_RESOLUTION_DEPTH {
            return None;
        }
        match symbol {
            Symbol::Function(index) => Some(index),
            Symbol::Binding(binding) => {
                // A single immutable function item alias has a definite target. Mutable
                // pointers and parameters need data-flow/interprocedural analysis.
                if binding.kind() != "let_declaration"
                    || nodes(binding).any(|node| node.kind() == "mutable_specifier")
                {
                    return None;
                }
                let pattern = binding.child_by_field_name("pattern")?;
                if pattern.kind() != "identifier" {
                    return None;
                }
                // Reassignment can occur even without `mut` on initially uninitialized
                // bindings. Only aliases with an initializer are considered here.
                let value = binding.child_by_field_name("value")?;
                let symbol = self.resolve_path(value, value, Namespace::Value, depth + 1)?;
                self.callable(symbol, depth + 1)
            }
            _ => None,
        }
    }

    fn resolve_type(
        &self,
        ty: Node<'tree>,
        context: Node<'tree>,
        depth: usize,
    ) -> Option<Node<'tree>> {
        if depth > MAX_RESOLUTION_DEPTH {
            return None;
        }
        match ty.kind() {
            "reference_type" => {
                self.resolve_type(ty.child_by_field_name("type")?, context, depth + 1)
            }
            "generic_type" => {
                self.resolve_type(ty.child_by_field_name("type")?, context, depth + 1)
            }
            // Erasing a generic argument is safe only when method selection is unique;
            // `member` rejects competing specialized impls.
            "bracketed_type" => self.resolve_type(children(ty).next()?, context, depth + 1),
            "qualified_type" => {
                self.resolve_type(ty.child_by_field_name("type")?, context, depth + 1)
            }
            _ => match self.resolve_path(ty, context, Namespace::Type, depth + 1)? {
                Symbol::Type(node) => Some(node),
                Symbol::Alias(node) => {
                    self.resolve_type(node.child_by_field_name("type")?, node, depth + 1)
                }
                _ => None,
            },
        }
    }

    fn self_type(&self, context: Node<'tree>, depth: usize) -> Option<Node<'tree>> {
        let implementation = ancestor(context, "impl_item")?;
        self.resolve_type(
            implementation.child_by_field_name("type")?,
            implementation,
            depth + 1,
        )
    }

    fn resolve_path(
        &self,
        path: Node<'tree>,
        context: Node<'tree>,
        namespace: Namespace,
        depth: usize,
    ) -> Option<Symbol<'tree>> {
        if depth > MAX_RESOLUTION_DEPTH {
            return None;
        }
        match path.kind() {
            "generic_function" => self.resolve_path(
                path.child_by_field_name("function")?,
                context,
                namespace,
                depth + 1,
            ),
            "generic_type" => self.resolve_path(
                path.child_by_field_name("type")?,
                context,
                namespace,
                depth + 1,
            ),
            "identifier" | "type_identifier" => {
                if self.text(path) == "Self" {
                    return self.self_type(context, depth + 1).map(Symbol::Type);
                }
                self.lookup(self.text(path), context, namespace, depth + 1)
            }
            "crate" => self.confirmed_crate_root(context).map(Symbol::Module),
            "self" => Some(Symbol::Module(module_scope(
                context,
                self.crate_root(context),
            ))),
            "super" => Some(Symbol::Module(parent_module(
                module_scope(context, self.crate_root(context)),
                self.crate_root(context),
            )?)),
            "scoped_identifier" | "scoped_type_identifier" => {
                let name = self.text(path.child_by_field_name("name")?);
                let Some(prefix) = path.child_by_field_name("path") else {
                    return if self.legacy_edition(context) {
                        let root = self.crate_root(context);
                        self.scope_symbol(
                            root.child_by_field_name("body").unwrap_or(root),
                            name,
                            namespace,
                            depth + 1,
                        )
                    } else {
                        self.external(name, context)
                    };
                };
                if prefix.kind() == "bracketed_type" {
                    let qualified = children(prefix).next()?;
                    let ty = self.resolve_type(qualified, context, depth + 1)?;
                    let trait_id = if qualified.kind() == "qualified_type" {
                        match self.resolve_path(
                            qualified.child_by_field_name("alias")?,
                            context,
                            Namespace::Type,
                            depth + 1,
                        )? {
                            Symbol::Trait(node) => Some(node.id()),
                            _ => return None,
                        }
                    } else {
                        None
                    };
                    return self
                        .member(ty, name, trait_id, None, context, depth + 1)
                        .map(Symbol::Function);
                }
                let owner = self.resolve_path(prefix, context, Namespace::Type, depth + 1)?;
                self.path_member(owner, name, namespace, context, depth + 1)
            }
            "parenthesized_expression" => {
                self.resolve_path(children(path).next()?, context, namespace, depth + 1)
            }
            _ => None,
        }
    }

    fn path_member(
        &self,
        owner: Symbol<'tree>,
        name: &str,
        namespace: Namespace,
        context: Node<'tree>,
        depth: usize,
    ) -> Option<Symbol<'tree>> {
        if depth > MAX_RESOLUTION_DEPTH {
            return None;
        }
        match owner {
            Symbol::Module(module) => {
                if name == "super" {
                    return parent_module(module, self.crate_root(module)).map(Symbol::Module);
                }
                let scope = module.child_by_field_name("body").unwrap_or(module);
                self.scope_symbol(scope, name, namespace, depth + 1)
            }
            Symbol::Type(ty) => {
                if ty.kind() == "enum_item"
                    && ty.child_by_field_name("body").is_some_and(|body| {
                        children(body).any(|variant| {
                            variant
                                .child_by_field_name("name")
                                .is_some_and(|v| self.text(v) == name)
                        })
                    })
                {
                    return Some(Symbol::Type(ty));
                }
                self.member(ty, name, None, None, context, depth + 1)
                    .map(Symbol::Function)
            }
            Symbol::Alias(alias) => {
                let ty = self.resolve_type(alias.child_by_field_name("type")?, alias, depth + 1)?;
                self.path_member(Symbol::Type(ty), name, namespace, context, depth + 1)
            }
            _ => None,
        }
    }

    fn local_binding(
        &self,
        node: Node<'tree>,
        context: Node<'tree>,
        name: &str,
    ) -> Option<Symbol<'tree>> {
        if node.kind() == "block" {
            let mut lets: Vec<_> = children(node)
                .filter(|child| {
                    child.kind() == "let_declaration" && child.end_byte() <= context.start_byte()
                })
                .collect();
            lets.reverse();
            for binding in lets {
                if binding
                    .child_by_field_name("pattern")
                    .is_some_and(|p| pattern_contains(p, name, self.source))
                {
                    return Some(Symbol::Binding(binding));
                }
            }
        }
        if matches!(node.kind(), "function_item" | "closure_expression") {
            if let Some(parameters) = node.child_by_field_name("parameters") {
                for parameter in children(parameters) {
                    let pattern = parameter
                        .child_by_field_name("pattern")
                        .unwrap_or(parameter);
                    if pattern_contains(pattern, name, self.source) {
                        return Some(Symbol::Binding(parameter));
                    }
                }
            }
        }
        if ((node.kind() == "for_expression"
            && node
                .child_by_field_name("body")
                .is_some_and(|body| contains(body, context)))
            || node.kind() == "match_arm")
            && node
                .child_by_field_name("pattern")
                .is_some_and(|pattern| pattern_contains(pattern, name, self.source))
        {
            return Some(Symbol::Unknown);
        }
        self.condition_binding(node, context, name)
    }

    fn condition_binding(
        &self,
        node: Node<'tree>,
        context: Node<'tree>,
        name: &str,
    ) -> Option<Symbol<'tree>> {
        if matches!(node.kind(), "if_expression" | "while_expression") {
            if let Some(condition) = node.child_by_field_name("condition") {
                let in_body = node
                    .child_by_field_name(if node.kind() == "if_expression" {
                        "consequence"
                    } else {
                        "body"
                    })
                    .is_some_and(|body| contains(body, context));
                for binding in nodes(condition).filter(|node| node.kind() == "let_condition") {
                    if (in_body
                        || (contains(condition, context)
                            && binding.end_byte() <= context.start_byte()))
                        && binding
                            .child_by_field_name("pattern")
                            .is_some_and(|pattern| pattern_contains(pattern, name, self.source))
                    {
                        return Some(Symbol::Unknown);
                    }
                }
            }
        }
        None
    }

    fn generic_parameter_shadows(&self, node: Node<'tree>, name: &str) -> bool {
        node.child_by_field_name("type_parameters")
            .is_some_and(|parameters| {
                children(parameters).any(|p| {
                    p.child_by_field_name("name")
                        .is_some_and(|p| self.text(p) == name)
                })
            })
    }

    fn lookup(
        &self,
        name: &str,
        context: Node<'tree>,
        namespace: Namespace,
        depth: usize,
    ) -> Option<Symbol<'tree>> {
        if depth > MAX_RESOLUTION_DEPTH {
            return None;
        }
        let mut scope = Some(context);
        let mut locals = true;
        while let Some(node) = scope {
            if matches!(namespace, Namespace::Value) && locals {
                if let Some(binding) = self.local_binding(node, context, name) {
                    return Some(binding);
                }
            }
            // Generic parameters shadow concrete type items; their dispatch is unknown.
            if matches!(namespace, Namespace::Type) && self.generic_parameter_shadows(node, name) {
                return Some(Symbol::Unknown);
            }
            if matches!(node.kind(), "block" | "source_file" | "declaration_list") {
                if let Some(symbol) = self.scope_symbol(node, name, namespace, depth + 1) {
                    return Some(symbol);
                }
                if node.kind() == "source_file"
                    || node.parent().is_some_and(|p| p.kind() == "mod_item")
                {
                    return if matches!(namespace, Namespace::Type) && !self.legacy_edition(context)
                    {
                        self.external(name, context)
                    } else {
                        None
                    };
                }
            }
            if node.kind() == "function_item" {
                locals = false;
            }
            scope = node.parent();
        }
        None
    }

    fn scope_symbol(
        &self,
        scope: Node<'tree>,
        name: &str,
        namespace: Namespace,
        depth: usize,
    ) -> Option<Symbol<'tree>> {
        if depth > MAX_RESOLUTION_DEPTH {
            return None;
        }
        let key = (scope.id(), name.to_owned(), namespace);
        if !self.active_lookups.borrow_mut().insert(key.clone()) {
            return None;
        }
        let result = self.scope_symbol_inner(scope, name, namespace, depth);
        self.active_lookups.borrow_mut().remove(&key);
        result
    }

    fn declared_symbol(
        &self,
        scope: Node<'tree>,
        name: &str,
        namespace: Namespace,
    ) -> Option<Symbol<'tree>> {
        if let Some(symbols) = self.symbols.get(&(scope.id(), name.to_owned())) {
            let mut symbols = symbols.iter().copied().filter(|symbol| match namespace {
                Namespace::Type => matches!(
                    symbol,
                    Symbol::Type(_) | Symbol::Alias(_) | Symbol::Module(_) | Symbol::Trait(_)
                ),
                Namespace::Value => !matches!(
                    symbol,
                    Symbol::Alias(_) | Symbol::Module(_) | Symbol::Trait(_)
                ),
            });
            if let Some(symbol) = symbols.next() {
                return Some(if symbols.next().is_none() {
                    symbol
                } else {
                    Symbol::Unknown
                });
            }
        }
        None
    }

    fn scope_symbol_inner(
        &self,
        scope: Node<'tree>,
        name: &str,
        namespace: Namespace,
        depth: usize,
    ) -> Option<Symbol<'tree>> {
        if let Some(symbol) = self.declared_symbol(scope, name, namespace) {
            return Some(symbol);
        }
        let mut candidates = ImportCandidates::default();
        for &item in self.import_items.get(&scope.id()).into_iter().flatten() {
            self.collect_import_candidates(item, name, namespace, depth, &mut candidates);
        }
        candidates.resolve()
    }

    fn collect_import_candidates(
        &self,
        item: Node<'tree>,
        name: &str,
        namespace: Namespace,
        depth: usize,
        candidates: &mut ImportCandidates<'tree>,
    ) {
        if item.kind() == "extern_crate_declaration" && matches!(namespace, Namespace::Type) {
            if let Some(crate_name) = item.child_by_field_name("name") {
                let alias = item.child_by_field_name("alias").unwrap_or(crate_name);
                if self.text(alias) == name {
                    candidates.explicit.push(
                        self.external(self.text(crate_name), item)
                            .unwrap_or(Symbol::Unknown),
                    );
                }
            }
        }
        if item.kind() == "use_declaration" {
            if let Some(argument) = item.child_by_field_name("argument") {
                for import in imports(argument, Vec::new(), self.source) {
                    self.collect_import(import, item, name, namespace, depth, candidates);
                }
            }
        }
        // Statement macros and foreign modules can introduce unknown names.
        if matches!(item.kind(), "macro_invocation" | "foreign_mod_item")
            || (item.kind() == "expression_statement"
                && children(item)
                    .next()
                    .is_some_and(|c| c.kind() == "macro_invocation"))
        {
            candidates.glob_unknown = true;
        }
    }

    fn collect_import(
        &self,
        import: Import,
        item: Node<'tree>,
        name: &str,
        namespace: Namespace,
        depth: usize,
        candidates: &mut ImportCandidates<'tree>,
    ) {
        if import.alias.as_deref() == Some(name) {
            candidates.explicit.push(
                self.resolve_segments(&import.path, item, namespace, depth + 1)
                    .unwrap_or(Symbol::Unknown),
            );
            return;
        }
        if import.alias.is_some() {
            return;
        }
        let Some(owner) = self.resolve_segments(&import.path, item, Namespace::Type, depth + 1)
        else {
            candidates.glob_unknown = true;
            return;
        };
        if owner == Symbol::Unknown {
            candidates.glob_unknown = true;
        }
        if let Some(symbol) = self.path_member(owner, name, namespace, item, depth + 1) {
            candidates.glob.push(symbol);
        }
    }

    fn resolve_segments(
        &self,
        path: &[String],
        context: Node<'tree>,
        namespace: Namespace,
        depth: usize,
    ) -> Option<Symbol<'tree>> {
        if depth > MAX_RESOLUTION_DEPTH {
            return None;
        }
        let (first, remaining) = path.split_first()?;
        let head_namespace = if remaining.is_empty() {
            namespace
        } else {
            Namespace::Type
        };
        let mut symbol = match first.as_str() {
            "crate" => Symbol::Module(self.confirmed_crate_root(context)?),
            "self" => Symbol::Module(module_scope(context, self.crate_root(context))),
            "super" => Symbol::Module(parent_module(
                module_scope(context, self.crate_root(context)),
                self.crate_root(context),
            )?),
            // In Rust 2018+, unqualified imports resolve from the current scope.
            // Do not fall back to the root: that could select an unrelated name.
            "" => {
                let (name, rest) = remaining.split_first()?;
                let owner = if self.legacy_edition(context) {
                    let root = self.crate_root(context);
                    self.scope_symbol(
                        root.child_by_field_name("body").unwrap_or(root),
                        name,
                        Namespace::Type,
                        depth + 1,
                    )?
                } else {
                    self.external(name, context)?
                };
                let mut symbol = owner;
                for (index, name) in rest.iter().enumerate() {
                    symbol = self.path_member(
                        symbol,
                        name,
                        if index + 1 == rest.len() {
                            namespace
                        } else {
                            Namespace::Type
                        },
                        context,
                        depth + 1,
                    )?;
                }
                return Some(symbol);
            }
            _ if self.legacy_edition(context) => {
                let root = self.crate_root(context);
                self.scope_symbol(
                    root.child_by_field_name("body").unwrap_or(root),
                    first,
                    head_namespace,
                    depth + 1,
                )?
            }
            _ => self.lookup(first, context, head_namespace, depth + 1)?,
        };
        for (index, segment) in remaining.iter().enumerate() {
            symbol = self.path_member(
                symbol,
                segment,
                if index + 1 == remaining.len() {
                    namespace
                } else {
                    Namespace::Type
                },
                context,
                depth + 1,
            )?;
        }
        Some(symbol)
    }

    fn member(
        &self,
        ty: Node<'tree>,
        name: &str,
        explicit_trait: Option<usize>,
        receiver: Option<u8>,
        context: Node<'tree>,
        depth: usize,
    ) -> Option<usize> {
        if depth > MAX_RESOLUTION_DEPTH {
            return None;
        }
        // Generic arguments are not substituted by this prototype. A specialized
        // or constrained impl can compete with a trait method, so reject it rather
        // than treating an erased receiver type as proof that the impl applies.
        if self.functions.iter().any(|function| {
            function.owner == Some(ty)
                && function
                    .node
                    .child_by_field_name("name")
                    .is_some_and(|n| self.text(n) == name)
                && function
                    .implementation
                    .is_some_and(|i| i.kind() == "impl_item" && !self.universal_impl(i))
        }) {
            return None;
        }
        let named = |function: &&Function<'tree>| {
            function.owner == Some(ty)
                && function
                    .node
                    .child_by_field_name("name")
                    .is_some_and(|n| self.text(n) == name)
                && (receiver.is_none() || function.method)
        };
        let inherent: Vec<_> = self
            .functions
            .iter()
            .enumerate()
            .filter(|(_, f)| {
                named(f)
                    && f.implementation
                        .is_some_and(|i| i.child_by_field_name("trait").is_none())
            })
            .map(|(i, _)| i)
            .collect();
        if receiver.is_none() && explicit_trait.is_none() && !inherent.is_empty() {
            return unique(&inherent);
        }
        let mut candidates = self.trait_members(ty, name, explicit_trait, receiver, context, depth);
        if let Some(mode) = receiver {
            let mut ranked = Vec::new();
            for index in inherent.iter().chain(candidates.iter()).copied() {
                let Some(target_mode) =
                    self.function_receiver_mode(self.functions[index].node, depth + 1)
                else {
                    continue;
                };
                let rank = match (mode, target_mode) {
                    (0, target) => target,
                    (1, 1) | (2, 2) => 0,
                    (1 | 2, 0) => 3,
                    (2, 1) => 4,
                    (1, 2) => 5,
                    _ => continue,
                };
                ranked.push(((rank, if inherent.contains(&index) { 0 } else { 1 }), index));
            }
            ranked.sort_unstable();
            let best = ranked.first()?.0;
            candidates = ranked
                .into_iter()
                .filter(|(rank, _)| *rank == best)
                .map(|(_, index)| index)
                .collect();
        }
        candidates.sort_unstable();
        candidates.dedup();
        unique(&candidates)
    }

    fn trait_members(
        &self,
        ty: Node<'tree>,
        name: &str,
        explicit_trait: Option<usize>,
        receiver: Option<u8>,
        context: Node<'tree>,
        depth: usize,
    ) -> Vec<usize> {
        let named = |function: &&Function<'tree>| {
            function.owner == Some(ty)
                && function
                    .node
                    .child_by_field_name("name")
                    .is_some_and(|n| self.text(n) == name)
                && (receiver.is_none() || function.method)
        };
        let mut candidates = Vec::new();
        for &implementation in &self.implementations {
            let Some(_) = implementation.child_by_field_name("trait") else {
                continue;
            };
            if self.implementation_type(implementation, depth + 1) != Some(ty) {
                continue;
            }
            let Some(Symbol::Trait(trait_node)) =
                self.implementation_trait(implementation, depth + 1)
            else {
                continue;
            };
            if let Some(explicit) = explicit_trait {
                if explicit != trait_node.id() {
                    continue;
                }
            } else {
                if !self.trait_visible(trait_node, context, depth + 1) {
                    continue;
                }
            }
            let overrides: Vec<_> = self
                .functions
                .iter()
                .enumerate()
                .filter(|(_, f)| {
                    named(f)
                        && f.implementation == Some(implementation)
                        && f.trait_id == Some(trait_node.id())
                })
                .map(|(i, _)| i)
                .collect();
            if !overrides.is_empty() {
                candidates.extend(overrides);
                continue;
            }
            for (index, function) in self.functions.iter().enumerate() {
                if function.implementation == Some(trait_node)
                    && function
                        .node
                        .child_by_field_name("name")
                        .is_some_and(|n| self.text(n) == name)
                    && (receiver.is_none() || function.method)
                {
                    candidates.push(index);
                }
            }
        }
        candidates
    }

    fn implementation_type(
        &self,
        implementation: Node<'tree>,
        depth: usize,
    ) -> Option<Node<'tree>> {
        // An impl's owner is independent of the call site. Preserve both the
        // remaining resolution budget and import-cycle guards when reusing it:
        // results obtained during an active lookup may depend on that lookup.
        let cacheable = self.active_lookups.borrow().is_empty();
        let key = (implementation.id(), depth);
        if cacheable {
            if let Some(&owner) = self.implementation_types.borrow().get(&key) {
                return owner;
            }
        }
        let owner = implementation
            .child_by_field_name("type")
            .and_then(|ty| self.resolve_type(ty, implementation, depth));
        if cacheable {
            self.implementation_types.borrow_mut().insert(key, owner);
        }
        owner
    }

    fn implementation_trait(
        &self,
        implementation: Node<'tree>,
        depth: usize,
    ) -> Option<Symbol<'tree>> {
        let cacheable = self.active_lookups.borrow().is_empty();
        let key = (implementation.id(), depth);
        if cacheable {
            if let Some(&target) = self.implementation_traits.borrow().get(&key) {
                return target;
            }
        }
        let target = implementation
            .child_by_field_name("trait")
            .and_then(|path| self.resolve_path(path, implementation, Namespace::Type, depth));
        if cacheable {
            self.implementation_traits.borrow_mut().insert(key, target);
        }
        target
    }

    fn universal_impl(&self, implementation: Node<'tree>) -> bool {
        let Some(ty) = implementation.child_by_field_name("type") else {
            return false;
        };
        if children(implementation).any(|n| n.kind() == "where_clause") {
            return false;
        }
        if ty.kind() != "generic_type" {
            return true;
        }
        let Some(parameters) = implementation.child_by_field_name("type_parameters") else {
            return false;
        };
        let mut names = HashSet::new();
        for parameter in children(parameters) {
            if parameter.child_by_field_name("bounds").is_some() {
                return false;
            }
            let Some(name) = parameter.child_by_field_name("name") else {
                return false;
            };
            names.insert(self.text(name));
        }
        let Some(arguments) = ty.child_by_field_name("type_arguments") else {
            return false;
        };
        let arguments: Vec<_> = children(arguments).map(|n| self.text(n)).collect();
        arguments.len() == names.len() && arguments.iter().all(|name| names.remove(name))
    }

    fn trait_visible(&self, trait_node: Node<'tree>, context: Node<'tree>, depth: usize) -> bool {
        if depth > MAX_RESOLUTION_DEPTH {
            return false;
        }
        let mut current = Some(context);
        while let Some(scope) = current {
            if matches!(scope.kind(), "source_file" | "block" | "declaration_list") {
                if children(scope).any(|item| item == trait_node) {
                    return true;
                }
                if self.imports_trait(scope, trait_node, depth) {
                    return true;
                }
                if scope.kind() == "source_file"
                    || scope.parent().is_some_and(|p| p.kind() == "mod_item")
                {
                    break;
                }
            }
            current = scope.parent();
        }
        false
    }

    fn imports_trait(&self, scope: Node<'tree>, trait_node: Node<'tree>, depth: usize) -> bool {
        for &item in self
            .import_items
            .get(&scope.id())
            .into_iter()
            .flatten()
            .filter(|item| item.kind() == "use_declaration")
        {
            let Some(argument) = item.child_by_field_name("argument") else {
                continue;
            };
            for import in imports(argument, Vec::new(), self.source) {
                if self.import_exposes_trait(&import, item, trait_node, depth) {
                    return true;
                }
            }
        }
        false
    }

    fn import_exposes_trait(
        &self,
        import: &Import,
        item: Node<'tree>,
        trait_node: Node<'tree>,
        depth: usize,
    ) -> bool {
        let target = self.resolve_segments(&import.path, item, Namespace::Type, depth + 1);
        if target == Some(Symbol::Trait(trait_node)) {
            return true;
        }
        if import.alias.is_some() {
            return false;
        }
        let Some(owner) = target else {
            return false;
        };
        let Some(name) = trait_node.child_by_field_name("name") else {
            return false;
        };
        self.path_member(owner, self.text(name), Namespace::Type, item, depth + 1)
            == Some(Symbol::Trait(trait_node))
    }

    fn default_trait_call(
        &self,
        caller: usize,
        name: &str,
        context: Node<'tree>,
        depth: usize,
    ) -> Option<usize> {
        if depth > MAX_RESOLUTION_DEPTH {
            return None;
        }
        let function = &self.functions[caller];
        let trait_node = function.implementation?;
        if function
            .node
            .child_by_field_name("name")
            .is_some_and(|n| self.text(n) == name)
        {
            return Some(caller);
        }
        // A default body can dispatch differently for each implementing type.
        // Add an edge only when all local implementations inheriting this body
        // select the same target. No local implementation means unknown dispatch.
        let mut targets = Vec::new();
        for &implementation in &self.implementations {
            let Some(_) = implementation.child_by_field_name("trait") else {
                continue;
            };
            if self.implementation_trait(implementation, depth + 1)
                != Some(Symbol::Trait(trait_node))
            {
                continue;
            }
            if self.functions.iter().any(|f| {
                f.implementation == Some(implementation)
                    && f.node.child_by_field_name("name").map(|n| self.text(n))
                        == function
                            .node
                            .child_by_field_name("name")
                            .map(|n| self.text(n))
            }) {
                continue;
            }
            let ty = self.implementation_type(implementation, depth + 1)?;
            targets.push(self.member(ty, name, Some(trait_node.id()), None, context, depth + 1)?);
        }
        targets.sort_unstable();
        targets.dedup();
        unique(&targets)
    }

    fn function_receiver_mode(&self, function: Node<'tree>, depth: usize) -> Option<u8> {
        let parameters = function.child_by_field_name("parameters")?;
        let parameter = children(parameters).find(|p| {
            p.kind() == "self_parameter"
                || p.child_by_field_name("pattern")
                    .is_some_and(|p| p.kind() == "self")
        })?;
        if parameter.kind() == "self_parameter" {
            if self.text(parameter).contains('&') {
                Some(
                    if children(parameter).any(|n| n.kind() == "mutable_specifier") {
                        2
                    } else {
                        1
                    },
                )
            } else {
                Some(0)
            }
        } else {
            self.type_mode(parameter.child_by_field_name("type")?, parameter, depth + 1)
        }
    }

    fn type_mode(&self, ty: Node<'tree>, context: Node<'tree>, depth: usize) -> Option<u8> {
        if depth > MAX_RESOLUTION_DEPTH {
            return None;
        }
        if ty.kind() == "reference_type" {
            if ty
                .child_by_field_name("type")
                .is_some_and(|t| t.kind() == "reference_type")
            {
                return None;
            }
            return Some(if children(ty).any(|n| n.kind() == "mutable_specifier") {
                2
            } else {
                1
            });
        }
        if let Some(Symbol::Alias(alias)) =
            self.resolve_path(ty, context, Namespace::Type, depth + 1)
        {
            return self.type_mode(alias.child_by_field_name("type")?, alias, depth + 1);
        }
        Some(0)
    }

    fn expression_mode(
        &self,
        expression: Node<'tree>,
        context: Node<'tree>,
        depth: usize,
    ) -> Option<u8> {
        if depth > MAX_RESOLUTION_DEPTH {
            return None;
        }
        match expression.kind() {
            "self" => self.function_receiver_mode(enclosing_function(expression)?, depth + 1),
            "identifier" => {
                match self.lookup(self.text(expression), context, Namespace::Value, depth + 1) {
                    Some(Symbol::Binding(binding)) => {
                        if binding
                            .child_by_field_name("pattern")
                            .is_some_and(|p| p.kind() != "identifier")
                        {
                            return None;
                        }
                        if let Some(ty) = binding.child_by_field_name("type") {
                            self.type_mode(ty, binding, depth + 1)
                        } else {
                            self.expression_mode(
                                binding.child_by_field_name("value")?,
                                binding,
                                depth + 1,
                            )
                        }
                    }
                    Some(Symbol::Type(_)) => Some(0),
                    _ => None,
                }
            }
            "reference_expression" => Some(
                if children(expression).any(|n| n.kind() == "mutable_specifier") {
                    2
                } else {
                    1
                },
            ),
            "parenthesized_expression" => {
                self.expression_mode(children(expression).next()?, context, depth + 1)
            }
            "call_expression" => {
                if let Some((index, _)) = self.resolve_call(expression, depth + 1) {
                    let function = self.functions[index].node;
                    self.type_mode(
                        function.child_by_field_name("return_type")?,
                        function,
                        depth + 1,
                    )
                } else {
                    Some(0)
                }
            }
            "field_expression" => {
                let (ty, owner) = self.field_type(expression, context, depth + 1)?;
                self.type_mode(ty, owner, depth + 1)
            }
            _ => Some(0),
        }
    }

    fn field_type(
        &self,
        expression: Node<'tree>,
        context: Node<'tree>,
        depth: usize,
    ) -> Option<(Node<'tree>, Node<'tree>)> {
        let owner =
            self.expression_type(expression.child_by_field_name("value")?, context, depth + 1)?;
        let name = self.text(expression.child_by_field_name("field")?);
        let fields = owner.child_by_field_name("body")?;
        let field = if fields.kind() == "ordered_field_declaration_list" {
            fields
                .children_by_field_name("type", &mut fields.walk())
                .nth(name.parse().ok()?)?
        } else {
            children(fields).find(|f| {
                f.child_by_field_name("name")
                    .is_some_and(|n| self.text(n) == name)
            })?
        };
        Some((field.child_by_field_name("type").unwrap_or(field), owner))
    }

    fn expression_type(
        &self,
        expression: Node<'tree>,
        context: Node<'tree>,
        depth: usize,
    ) -> Option<Node<'tree>> {
        if depth > MAX_RESOLUTION_DEPTH {
            return None;
        }
        match expression.kind() {
            "self" => self.self_type(context, depth + 1),
            "identifier" => {
                match self.lookup(self.text(expression), context, Namespace::Value, depth + 1)? {
                    Symbol::Binding(binding) => {
                        if let Some(pattern) = binding.child_by_field_name("pattern") {
                            if pattern.kind() != "identifier" {
                                return None;
                            }
                        }
                        if let Some(ty) = binding.child_by_field_name("type") {
                            return self.resolve_type(ty, binding, depth + 1);
                        }
                        self.expression_type(
                            binding.child_by_field_name("value")?,
                            binding,
                            depth + 1,
                        )
                    }
                    Symbol::Type(ty) => Some(ty),
                    _ => None,
                }
            }
            "reference_expression" => {
                self.expression_type(expression.child_by_field_name("value")?, context, depth + 1)
            }
            "parenthesized_expression" => {
                self.expression_type(children(expression).next()?, context, depth + 1)
            }
            // Deref, Not and Neg can return a different type. Until operator
            // targets are resolved, the operand does not prove receiver identity.
            "unary_expression" => None,
            "struct_expression" => {
                self.resolve_type(expression.child_by_field_name("name")?, context, depth + 1)
            }
            "call_expression" => {
                if let Some((function, _)) = self.resolve_call(expression, depth + 1) {
                    let f = &self.functions[function];
                    return self.resolve_type(
                        f.node.child_by_field_name("return_type")?,
                        f.node,
                        depth + 1,
                    );
                }
                let callee = expression.child_by_field_name("function")?;
                match self.resolve_path(callee, context, Namespace::Value, depth + 1)? {
                    Symbol::Type(ty) => Some(ty),
                    _ => None,
                }
            }
            "field_expression" => {
                let (ty, owner) = self.field_type(expression, context, depth + 1)?;
                self.resolve_type(ty, owner, depth + 1)
            }
            "scoped_identifier" | "scoped_type_identifier" => {
                match self.resolve_path(expression, context, Namespace::Value, depth + 1)? {
                    Symbol::Type(ty) => Some(ty),
                    _ => None,
                }
            }
            _ => None,
        }
    }
}

fn unique(values: &[usize]) -> Option<usize> {
    if values.len() == 1 {
        Some(values[0])
    } else {
        None
    }
}
fn nodes(node: Node<'_>) -> impl Iterator<Item = Node<'_>> {
    std::iter::once(node).chain(NodeIterator::new(node, |_| true))
}
fn children(node: Node<'_>) -> impl Iterator<Item = Node<'_>> {
    // Indexed child access walks preceding siblings again for every index.
    // A cursor visits wide module and import lists in linear time.
    let mut cursor = node.walk();
    let mut first = true;
    std::iter::from_fn(move || loop {
        let found = if first {
            first = false;
            cursor.goto_first_child()
        } else {
            cursor.goto_next_sibling()
        };
        if !found {
            return None;
        }
        let child = cursor.node();
        if child.is_named() {
            return Some(child);
        }
    })
}
fn contains(outer: Node<'_>, inner: Node<'_>) -> bool {
    outer.start_byte() <= inner.start_byte() && inner.end_byte() <= outer.end_byte()
}
fn ancestor<'tree>(node: Node<'tree>, kind: &str) -> Option<Node<'tree>> {
    let mut current = Some(node);
    while let Some(node) = current {
        if node.kind() == kind {
            return Some(node);
        }
        current = node.parent();
    }
    None
}
fn enclosing_function(node: Node<'_>) -> Option<Node<'_>> {
    let mut current = node.parent();
    while let Some(node) = current {
        match node.kind() {
            "function_item" => return Some(node),
            "mod_item" | "impl_item" | "trait_item" | "const_item" | "static_item" => return None,
            _ => current = node.parent(),
        }
    }
    None
}
fn module_scope<'tree>(node: Node<'tree>, root: Node<'tree>) -> Node<'tree> {
    ancestor(node, "mod_item").unwrap_or(root)
}
fn parent_module<'tree>(module: Node<'tree>, root: Node<'tree>) -> Option<Node<'tree>> {
    if module == root {
        return None;
    }
    module.parent().map(|parent| module_scope(parent, root))
}
fn pattern_contains(node: Node<'_>, name: &str, source: &str) -> bool {
    nodes(node).any(|node| {
        node.kind() == "macro_invocation"
            || (matches!(
                node.kind(),
                "identifier" | "shorthand_field_identifier" | "self"
            ) && source[node.byte_range()].trim_start_matches("r#") == name)
    })
}

struct Import {
    path: Vec<String>,
    alias: Option<String>,
}
fn path_segments(node: Node<'_>, source: &str) -> Vec<String> {
    match node.kind() {
        "scoped_identifier" | "scoped_type_identifier" => {
            if node.child_by_field_name("path").is_none() {
                return vec![
                    String::new(),
                    source[node.child_by_field_name("name").unwrap().byte_range()]
                        .trim_start_matches("r#")
                        .to_owned(),
                ];
            }
            let mut path = node
                .child_by_field_name("path")
                .map(|n| path_segments(n, source))
                .unwrap_or_default();
            if let Some(name) = node.child_by_field_name("name") {
                path.push(
                    source[name.byte_range()]
                        .trim_start_matches("r#")
                        .to_owned(),
                );
            }
            path
        }
        _ => vec![source[node.byte_range()]
            .trim_start_matches("r#")
            .to_owned()],
    }
}
fn imports(node: Node<'_>, prefix: Vec<String>, source: &str) -> Vec<Import> {
    match node.kind() {
        "use_list" => children(node)
            .flat_map(|child| imports(child, prefix.clone(), source))
            .collect(),
        "scoped_use_list" => {
            let mut path = prefix;
            if let Some(parent) = node.child_by_field_name("path") {
                path.extend(path_segments(parent, source));
            }
            node.child_by_field_name("list")
                .map(|list| imports(list, path, source))
                .unwrap_or_default()
        }
        "use_as_clause" => {
            let mut path = prefix;
            if let Some(parent) = node.child_by_field_name("path") {
                path.extend(path_segments(parent, source));
            }
            if path.last().is_some_and(|s| s == "self") && path.len() > 1 {
                path.pop();
            }
            vec![Import {
                path,
                alias: node.child_by_field_name("alias").map(|alias| {
                    source[alias.byte_range()]
                        .trim_start_matches("r#")
                        .to_owned()
                }),
            }]
        }
        "use_wildcard" => {
            let mut path = prefix;
            if let Some(parent) = children(node).next() {
                path.extend(path_segments(parent, source));
            }
            vec![Import { path, alias: None }]
        }
        _ => {
            let mut path = prefix;
            path.extend(path_segments(node, source));
            if path.last().is_some_and(|s| s == "self") && path.len() > 1 {
                path.pop();
            }
            let alias = path.last().cloned();
            vec![Import { path, alias }]
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tree::parse_rust_code;

    #[test]
    fn merged_target_ranges_count_each_function_once() {
        let source = "fn a() { a(); a(); } fn b() { b(); }";
        let tree = parse_rust_code(source).unwrap();
        let ranges = nodes(tree.root_node())
            .filter(|node| node.kind() == "call_expression")
            .filter_map(|node| node.child_by_field_name("function"))
            .map(|node| (node.start_byte(), node.end_byte()))
            .collect();
        let recursion = Recursion::for_file(tree.root_node(), &ranges);
        assert_eq!(recursion.calls.len(), 2);
    }

    fn recursive_names(source: &str) -> Vec<String> {
        let tree = parse_rust_code(source).unwrap();
        assert!(
            !tree.root_node().has_error(),
            "Invalid test fixture: {}\n{}",
            source,
            tree.root_node().to_sexp()
        );
        let recursion = Recursion::new(tree.root_node(), source);
        let mut functions: Vec<_> = nodes(tree.root_node())
            .filter(|n| n.kind() == "function_item")
            .collect();
        functions.sort_by_key(|node| node.start_byte());
        functions
            .into_iter()
            .filter(|node| recursion.location(*node).is_some())
            .map(|node| source[node.child_by_field_name("name").unwrap().byte_range()].to_owned())
            .collect()
    }

    fn check(source: &str, expected: &[&str]) {
        assert_eq!(recursive_names(source), expected, "{source}");
    }

    #[test]
    fn cycles_score_each_member_once_and_exclude_callers() {
        check(
            "fn entry() { a(); } fn a() { b(); b(); } fn b() { c(); } fn c() { a(); } fn leaf() {}",
            &["a", "b", "c"],
        );
        check("fn a() { b(); } fn b() { c(); } fn c() {}", &[]);
        check("fn a() { a(); b(); } fn b() { b(); }", &["a", "b"]);
        check(
            "fn outer() { fn a() { b(); } fn b() { a(); } a(); }",
            &["a", "b"],
        );
    }

    #[test]
    fn qualified_paths_respect_module_boundaries() {
        check(
            "fn a() { m::b(); } mod m { pub fn b() { crate::a(); } }",
            &["a", "b"],
        );
        check(
            "mod m { fn a() { self::b(); } fn b() { super::m::a(); } }",
            &["a", "b"],
        );
        check(
            "mod m { mod n { fn a() { super::super::m::n::a(); } } }",
            &["a"],
        );
        check("fn a() { a(); } mod m { fn a() { a(); } }", &["a", "a"]);
        check("fn a() { m::b(); } mod m { fn b() { a(); } }", &[]);
        check("fn a() { ::external::a(); }", &[]);
    }

    #[test]
    fn imports_aliases_reexports_and_globs_resolve_locally() {
        check(
            "use crate::m::b as next; fn a() { next(); } mod m { pub fn b() { crate::a(); } }",
            &["a", "b"],
        );
        check(
            "mod m { use super::{a as back}; pub fn b() { back(); } } fn a() { m::b(); }",
            &["b", "a"],
        );
        check(
            "mod m { pub fn a() { crate::back(); } } pub use crate::m::a as back;",
            &["a"],
        );
        check(
            "mod m { pub fn a() { a(); } } use crate::m::*; fn entry() { a(); }",
            &["a"],
        );
        check("mod m { pub fn a() { crate::entry(); } } use crate::m::{self as alias, a}; fn entry() { alias::a(); }", &["a", "entry"]);
        check(
            "mod m { pub use crate::a as back; } fn a() { m::back(); }",
            &["a"],
        );
        check(
            "use crate::b as a; use crate::a as b; fn entry() { a(); }",
            &[],
        );
        check("use external::a; fn entry() { a(); }", &[]);
    }

    #[test]
    fn inherent_methods_and_associated_functions_form_cycles() {
        check("struct S; impl S { fn a(&self) { self.b(); } fn b(&self) { self.a(); } fn entry(&self) { self.a(); } }", &["a", "b"]);
        check(
            "struct S; impl S { fn a() { Self::b(); } fn b() { S::a(); } }",
            &["a", "b"],
        );
        check(
            "struct S; impl S { fn a(&self) { <Self>::a(self); } }",
            &["a"],
        );
        check("struct S; struct T; impl S { fn same(&self, t: &T) { t.same(); } } impl T { fn same(&self) {} }", &[]);
        check("mod m { pub struct S; impl S { pub fn a() { crate::entry(); } } } fn entry() { m::S::a(); }", &["a", "entry"]);
    }

    #[test]
    fn receiver_types_flow_from_parameters_locals_constructors_and_returns() {
        check(
            "struct S; impl S { fn a(&self) { helper(self); } } fn helper(s: &S) { s.a(); }",
            &["a", "helper"],
        );
        check(
            "struct S; impl S { fn a(&self) { let other = S; other.a(); } }",
            &["a"],
        );
        check(
            "struct S {} impl S { fn a(&self) { let other = S {}; (&other).a(); } }",
            &["a"],
        );
        check(
            "struct S; impl S { fn new() -> Self { S } fn a(&self) { Self::new().a(); } }",
            &["a"],
        );
        check(
            "struct S; impl S { fn a(&self) { let s: &S = self; s.a(); } }",
            &["a"],
        );
        check("struct S; impl S { fn a(&self) { helper(); } } fn make() -> S { S } fn helper() { let s = make(); s.a(); }", &["a", "helper"]);
        check(
            "struct S; type Alias = S; impl S { fn a() { Alias::a(); } }",
            &["a"],
        );
        check(
            "struct S; use crate::S as Alias; impl S { fn a() { Alias::a(); } }",
            &["a"],
        );
    }

    #[test]
    fn fields_and_generic_owners_have_concrete_receiver_types() {
        check("struct S; struct Holder { s: S } impl S { fn a(&self) { helper(); } } fn helper() { let h: Holder = unknown(); h.s.a(); }", &["a", "helper"]);
        check("struct S; struct Holder(S); impl S { fn a(&self) { helper(); } } fn helper(h: Holder) { h.0.a(); }", &["a", "helper"]);
        check(
            "struct S<T>(T); impl<T> S<T> { fn a(&self) { self.a(); } }",
            &["a"],
        );
        check("struct S; impl S { fn a() { S::a::<u8>(); } }", &["a"]);
    }

    #[test]
    fn unary_operators_do_not_preserve_the_operand_type() {
        check("struct Inner; impl Inner { fn len(&self) {} } struct W; impl std::ops::Deref for W { type Target = Inner; fn deref(&self) -> &Inner { unknown() } } impl W { fn len(&self) { (**self).len(); } }", &[]);
        check(
            "struct W; impl W { fn len(&self) { (!self).len(); (-self).len(); } }",
            &[],
        );
        check(
            "struct W; impl W { fn len(&self) { (self).len(); } }",
            &["len"],
        );
    }

    #[test]
    fn tuple_field_indices_ignore_visibility_attributes_and_comments() {
        check("struct A; struct B; pub struct W(#[allow(dead_code)] pub A, /* field */ pub(crate) B); impl A { fn a(&self) { first(unknown()); } } impl B { fn b(&self) { second(unknown()); } } fn first(w: W) { w.0.a(); } fn second(w: W) { w.1.b(); }", &["a", "b", "first", "second"]);
        check("struct A; struct B; pub struct W(pub A, pub B); impl A { fn a(&self) { helper(unknown()); } } impl B { fn a(&self) {} } fn helper(w: W) { w.1.a(); }", &[]);
    }

    #[test]
    fn annotated_destructuring_does_not_type_each_binding_as_the_container() {
        check("struct Inner; impl Inner { fn m(&self) {} } struct S(Inner); impl S { fn m(&self) { f(unknown()); } } fn f(S(inner): S) { inner.m(); }", &[]);
        check("struct Inner; impl Inner { fn m(&self) {} } struct S(Inner); impl S { fn m(&self) { f(); } } fn f() { let S(inner): S = unknown(); inner.m(); }", &[]);
        check("struct Inner; impl Inner { fn m(&self) {} } struct S { inner: Inner } impl S { fn m(&self) { f(unknown()); } } fn f(S { inner }: S) { inner.m(); }", &[]);
        check(
            "struct S; impl S { fn m(&self) { f(unknown()); } } fn f(s: S) { s.m(); }",
            &["m", "f"],
        );
    }

    #[test]
    fn trait_impls_and_fully_qualified_calls_disambiguate() {
        check(
            "struct S; trait T { fn a(&self); } impl T for S { fn a(&self) { self.a(); } }",
            &["a"],
        );
        check("struct S; trait T { fn a(&self); } impl T for S { fn a(&self) { <S as T>::a(self); } }", &["a"]);
        check(
            "struct S; trait T { fn a(&self); } impl T for S { fn a(&self) { T::a(self); } }",
            &["a"],
        );
        check("struct S; trait T { fn a(&self); } trait U { fn a(&self); } impl T for S { fn a(&self) { <S as U>::a(self); } } impl U for S { fn a(&self) { <S as T>::a(self); } }", &["a", "a"]);
        check("struct S; trait T { fn a(&self); } trait U { fn a(&self); } impl T for S { fn a(&self) { self.a(); } } impl U for S { fn a(&self) { self.a(); } }", &[]);
        check("struct S; trait T { fn a(&self); } impl S { fn a(&self) {} } impl T for S { fn a(&self) { self.a(); } }", &[]);
        check(
            "trait T { fn a(&self) {} } struct S; impl T for S {} fn entry(s: &S) { s.a(); }",
            &[],
        );
    }

    #[test]
    fn immutable_function_aliases_have_definite_targets() {
        check(
            "fn a() { let next = b; next(); } fn b() { a(); }",
            &["a", "b"],
        );
        check(
            "fn a() { let next = a; let alias = next; alias(); }",
            &["a"],
        );
        check(
            "fn a() { let mut next = a; next = other; next(); } fn other() {}",
            &[],
        );
        check("fn a(next: fn()) { next(); }", &[]);
        check("fn a() { let a = || {}; a(); }", &[]);
    }

    #[test]
    fn shadowing_is_lexical_and_initializers_use_previous_binding() {
        check("fn a() { let a = a(); a(); }", &["a"]);
        check(
            "fn a() { if let Some(a) = unknown() { a(); } else { a(); } }",
            &["a"],
        );
        check("fn a() { for a in a() { a(); } }", &["a"]);
        check(
            "fn a() { match a() { Some(a) if a() => a(), _ => () } }",
            &["a"],
        );
        check("fn a() { let a = || {}; fn b() { a(); } }", &[]);
        check(
            "struct S; fn entry<S>(s: S) { s.a(); } impl S { fn a(&self) { entry(self); } }",
            &[],
        );
        check("struct S; fn entry(s: &dyn T) { s.a(); } trait T { fn a(&self); } impl T for S { fn a(&self) { entry(self); } }", &[]);
    }

    #[test]
    fn local_imports_and_trait_aliases_are_visible() {
        check("mod m { mod inner { pub fn a() { super::entry(); } } use inner::a as next; fn entry() { next(); } }", &["a", "entry"]);
        check(
            "fn entry() { mod inner { pub fn a() { crate::entry(); } } use inner::a; a(); }",
            &["entry", "a"],
        );
        check("mod m { pub trait T { fn a(&self); } } struct S; impl m::T for S { fn a(&self) { entry(self); } } use m::T as Alias; fn entry(s: &S) { s.a(); }", &["a", "entry"]);
        check("mod m { pub trait T { fn a(&self); } } struct S; impl m::T for S { fn a(&self) { entry(self); } } use m::T as _; fn entry(s: &S) { s.a(); }", &["a", "entry"]);
        check("mod m { pub fn a() { crate::entry(); } } use m::*; use external::*; fn entry() { a(); }", &[]);
        check("mod m { pub fn a() { crate::entry(); } } use m::a; use external::*; fn entry() { a(); }", &["a", "entry"]);
        check(
            "mod m { pub fn a() { crate::entry(); } } use ::m::a; fn entry() { a(); }",
            &[],
        );
    }

    #[test]
    fn receiver_candidate_order_precedes_inherent_trait_priority() {
        check("struct S; trait T { fn a(&self); } impl S { fn a(&mut self) { let mut s = S; s.a(); } } impl T for S { fn a(&self) {} }", &[]);
        check("struct S; trait T { fn a(&self); } impl S { fn a(&mut self) { self.a(); } } impl T for S { fn a(&self) {} }", &["a"]);
        check("struct S; trait T { fn a(&self); } impl S { fn a(self) {} } impl T for S { fn a(&self) { self.a(); } }", &["a"]);
    }

    #[test]
    fn default_trait_methods_use_only_definite_dispatch_targets() {
        check("trait T { fn a(&self) { self.a(); } }", &["a"]);
        check("trait T { fn a(&self) { self.b(); } fn b(&self) { self.a(); } } struct S; impl T for S {}", &["a", "b"]);
        check("trait T { fn a(&self) { self.b(); } fn b(&self); } struct S; impl T for S { fn b(&self) { self.a(); } }", &["a", "b"]);
        check("trait T { fn a(&self) { self.b(); } fn b(&self); } struct S; struct U; impl T for S { fn b(&self) {} } impl T for U { fn b(&self) {} }", &[]);
    }

    #[test]
    fn specialized_impls_and_cyclic_aliases_are_not_guessed() {
        check("struct S<T>(T); impl S<u8> { fn a(&self) { self.a(); } } impl S<u16> { fn a(&self) { self.a(); } }", &[]);
        check("type A = B; type B = A; fn entry(a: A) { a.entry(); }", &[]);
        check("struct S; impl S { fn a(&self) { unknown().a(); } }", &[]);
    }

    #[test]
    fn cycle_location_points_to_a_cycle_edge_not_an_unrelated_call() {
        let source = "fn a() { leaf(); b(); b(); } fn b() { a(); } fn leaf() {}";
        let tree = parse_rust_code(source).unwrap();
        let recursion = Recursion::new(tree.root_node(), source);
        let a = nodes(tree.root_node())
            .find(|node| {
                node.kind() == "function_item"
                    && node
                        .child_by_field_name("name")
                        .is_some_and(|name| &source[name.byte_range()] == "a")
            })
            .unwrap();
        let location = recursion.location(a).unwrap();
        assert_eq!(&source[location.start_byte..location.end_byte], "b");
        assert_eq!(location.start_byte, source.find("b();").unwrap());
    }

    #[test]
    fn compiler_checked_fixture_resolves_a_cycle_across_methods_and_modules() {
        check(
            include_str!("../tests/fixtures/recursion.rs"),
            &["start", "run", "step"],
        );
    }

    #[test]
    fn long_cycles_do_not_use_recursive_graph_traversal() {
        let mut source = String::new();
        for index in 0..2000 {
            source.push_str(&format!("fn f{index}() {{ f{}(); }}\n", (index + 1) % 2000));
        }
        assert_eq!(recursive_names(&source).len(), 2000);
    }

    #[test]
    fn wide_modules_preserve_import_cycles_and_unknown_names() {
        let mut source = String::from("mod wide {");
        for index in 0..2000 {
            source.push_str(&format!("struct Unused{index};\n"));
        }
        source.push_str(
            "pub fn hop() { super::entry(); } } use wide::hop as next; fn entry() { next(); }",
        );
        check(&source, &["hop", "entry"]);
        // Macro statements and foreign declarations still make glob lookup
        // uncertain, even though unrelated declarations are no longer scanned.
        check(
            "mod m { pub fn a() { super::entry(); } } use m::*; make_names!(); fn entry() { a(); }",
            &[],
        );
        check("mod m { pub fn a() { super::entry(); } } use m::*; extern \"C\" { fn a(); } fn entry() { a(); }", &[]);
    }

    #[test]
    fn cached_impl_owners_preserve_the_resolution_budget() {
        let source = "struct S; type Alias = S; trait T { fn hop(&self); } impl T for Alias { fn hop(&self) {} }";
        let tree = parse_rust_code(source).unwrap();
        let resolver = Resolver::with_crates(tree.root_node(), source, &HashMap::new(), true);
        let implementation = resolver.implementations[0];
        let owner = resolver.implementation_type(implementation, 0).unwrap();
        assert_eq!(
            resolver.text(owner.child_by_field_name("name").unwrap()),
            "S"
        );
        assert!(resolver
            .implementation_type(implementation, MAX_RESOLUTION_DEPTH)
            .is_none());
        assert_eq!(resolver.implementation_type(implementation, 0), Some(owner));
    }

    #[test]
    fn reachable_dependency_bodies_preserve_workspace_cycles() {
        let source = "mod dependency { pub fn hop() { super::app::entry(); } fn unused() { unused(); } } mod app { pub fn entry() { super::dependency::hop(); } }";
        let tree = parse_rust_code(source).unwrap();
        let root = tree.root_node();
        let app = children(root).last().unwrap();
        let ranges = [(app.start_byte(), app.end_byte())];
        let reachable = Recursion::reachable_from(root, source, &HashMap::new(), &ranges);
        let full = Recursion::new(root, source);
        for function in nodes(root).filter(|n| n.kind() == "function_item") {
            let name = &source[function.child_by_field_name("name").unwrap().byte_range()];
            if name == "unused" {
                assert!(full.location(function).is_some());
                assert!(reachable.location(function).is_none());
            } else {
                assert_eq!(reachable.location(function), full.location(function));
                assert!(reachable.location(function).is_some());
            }
        }
    }
}
