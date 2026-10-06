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
//! Cargo source discovery, virtual module trees, and original-file source maps.
use crate::recursion::{CrateContext, Recursion};
use crate::tree::parse_rust_code;
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tree_sitter::Node;

const MAX_PROJECT_BYTES: usize = 128 * 1024 * 1024;
const MAX_METADATA_BYTES: u64 = 32 * 1024 * 1024;
const MAX_MODULE_DEPTH: usize = 64;

type Ranges = HashSet<(usize, usize)>;

#[derive(Default)]
pub struct Project {
    files: HashMap<PathBuf, (Arc<String>, Ranges)>,
    roots: HashSet<PathBuf>,
}

impl Project {
    #[cfg(test)]
    pub fn load(manifests: &[String], overrides: HashMap<String, String>) -> (Self, Vec<String>) {
        Self::load_with_roots(manifests, overrides, |_| {})
    }

    pub fn load_with_roots(
        manifests: &[String],
        overrides: HashMap<String, String>,
        on_roots: impl FnOnce(&[PathBuf]),
    ) -> (Self, Vec<String>) {
        let mut warnings = Vec::new();
        let mut packages = HashMap::new();
        let mut workspace = HashSet::new();
        let mut dependencies = HashMap::new();
        let mut discovered_manifests = HashSet::new();
        for manifest in manifests {
            let path = absolute(Path::new(manifest));
            if !discovered_manifests.insert(path.clone()) {
                continue;
            }
            let metadata = match cargo_metadata(&path, false) {
                Ok(metadata) => metadata,
                Err(error) => {
                    warnings.push(format!("Dependency resolution unavailable for {}: {error}. Using local targets where possible.", path.display()));
                    match cargo_metadata(&path, true) {
                        Ok(metadata) => metadata,
                        Err(error) => {
                            warnings.push(format!(
                                "Cargo target discovery unavailable for {}: {error}",
                                path.display()
                            ));
                            continue;
                        }
                    }
                }
            };
            for package in array(&metadata["packages"]) {
                if let Some(id) = package["id"].as_str() {
                    packages.insert(id.to_owned(), package.clone());
                }
            }
            for id in array(&metadata["workspace_members"]).filter_map(Value::as_str) {
                workspace.insert(id.to_owned());
                if let Some(manifest) = packages.get(id).and_then(|p| p["manifest_path"].as_str()) {
                    discovered_manifests.insert(absolute(Path::new(manifest)));
                }
            }
            for node in array(&metadata["resolve"]["nodes"]) {
                if let Some(id) = node["id"].as_str() {
                    dependencies.insert(id.to_owned(), node["deps"].clone());
                }
            }
        }
        let crates = crate_targets(&packages, &workspace, &dependencies);
        let mut roots: Vec<_> = crates.iter().map(|krate| krate.root.clone()).collect();
        roots.sort();
        roots.dedup();
        // Publish confirmed identities before parsing the combined graph, so the
        // scanner can retain them if that more expensive phase fails.
        on_roots(&roots);
        Self::from_crates(crates, overrides, warnings)
    }

    fn from_crates(
        crates: Vec<Crate>,
        overrides: HashMap<String, String>,
        mut warnings: Vec<String>,
    ) -> (Self, Vec<String>) {
        let mut builder = Builder {
            text: String::new(),
            spans: Vec::new(),
            sources: overrides
                .into_iter()
                .map(|(p, s)| (absolute(Path::new(&p)), Arc::new(s)))
                .collect(),
            loaded: HashSet::new(),
            active: HashSet::new(),
            warnings: Vec::new(),
        };
        let mut contexts = HashMap::new();
        for krate in &crates {
            let name = &krate.name;
            let start = builder.text.len();
            builder.text.push_str(&format!("mod {name} {{\n"));
            if !builder.load(
                &krate.root,
                krate.root.parent().unwrap_or(Path::new(".")),
                0,
            ) {
                builder.text.truncate(start);
                continue;
            }
            builder.text.push_str("\n}\n");
            contexts.insert(
                name.clone(),
                CrateContext {
                    dependencies: krate.dependencies.clone(),
                    edition: krate.edition,
                },
            );
        }
        let mut project = Self {
            roots: crates.iter().map(|krate| absolute(&krate.root)).collect(),
            ..Self::default()
        };
        for path in &builder.loaded {
            if let Some(source) = builder.sources.get(path) {
                project
                    .files
                    .insert(path.clone(), (source.clone(), HashSet::new()));
            }
        }
        match parse_rust_code(&builder.text) {
            Ok(tree) => {
                let recursion = Recursion::in_project(tree.root_node(), &builder.text, &contexts);
                for (start, end) in recursion.ranges() {
                    let index = builder
                        .spans
                        .partition_point(|span| span.virtual_start <= start);
                    if let Some(span) = index
                        .checked_sub(1)
                        .and_then(|index| builder.spans.get(index))
                    {
                        if end <= span.virtual_start + span.length {
                            if let Some((_, ranges)) = project.files.get_mut(&span.path) {
                                ranges.insert((
                                    span.original_start + start - span.virtual_start,
                                    span.original_start + end - span.virtual_start,
                                ));
                            }
                        }
                    }
                }
            }
            Err(error) => {
                project.files.clear();
                warnings.push(format!("Project syntax tree unavailable: {error:?}"));
            }
        }
        warnings.extend(builder.warnings);
        warnings.sort();
        warnings.dedup();
        if warnings.len() > 20 {
            let remaining = warnings.len() - 20;
            warnings.truncate(20);
            warnings.push(format!(
                "{remaining} additional project source warnings omitted."
            ));
        }
        (project, warnings)
    }

    pub fn is_root(&self, path: &str) -> bool {
        self.roots.contains(&absolute(Path::new(path)))
    }

    pub fn ranges(&self, path: &str, source: &str) -> Option<&Ranges> {
        let (snapshot, ranges) = self.files.get(&absolute(Path::new(path)))?;
        // A file changed after indexing: do not apply offsets from an old snapshot.
        if snapshot.as_str() != source {
            return None;
        }
        Some(ranges)
    }
}

struct Crate {
    name: String,
    root: PathBuf,
    edition: u16,
    dependencies: HashMap<String, String>,
}

fn crate_targets(
    packages: &HashMap<String, Value>,
    workspace: &HashSet<String>,
    resolve: &HashMap<String, Value>,
) -> Vec<Crate> {
    let selected = select_targets(packages, workspace);
    let libraries: HashMap<_, _> = selected
        .iter()
        .enumerate()
        .filter(|(_, (_, _, lib, _))| *lib)
        .map(|(index, (id, _, _, _))| (id.clone(), format!("__sonar_crate_{index}")))
        .collect();
    let manifest_packages: HashMap<_, _> = packages
        .iter()
        .filter_map(|(id, p)| {
            p["manifest_path"]
                .as_str()
                .map(|p| (absolute(Path::new(p)), id))
        })
        .collect();
    selected
        .into_iter()
        .enumerate()
        .map(|(index, (id, target, library, root))| {
            let aliases = dependency_aliases(&id, packages, resolve, &manifest_packages);
            let bindings = dependency_bindings(&id, &packages[&id], library, aliases, &libraries);
            Crate {
                name: format!("__sonar_crate_{index}"),
                root,
                edition: target["edition"]
                    .as_str()
                    .or_else(|| packages[&id]["edition"].as_str())
                    .and_then(|s| s.parse().ok())
                    .unwrap_or(2021),
                dependencies: bindings,
            }
        })
        .collect()
}

type SelectedTarget = (String, Value, bool, PathBuf);

fn select_targets(
    packages: &HashMap<String, Value>,
    workspace: &HashSet<String>,
) -> Vec<SelectedTarget> {
    let mut selected = Vec::new();
    let mut ids: Vec<_> = packages.keys().collect();
    ids.sort();
    for id in ids {
        let package = &packages[id];
        for target in array(&package["targets"]) {
            let kinds: Vec<_> = array(&target["kind"]).filter_map(Value::as_str).collect();
            let library = kinds.iter().any(|kind| {
                matches!(
                    *kind,
                    "lib" | "rlib" | "dylib" | "cdylib" | "staticlib" | "proc-macro"
                )
            });
            if !library && (!workspace.contains(id) || kinds.contains(&"custom-build")) {
                continue;
            }
            if let Some(path) = target["src_path"].as_str() {
                selected.push((
                    id.clone(),
                    target.clone(),
                    library,
                    absolute(Path::new(path)),
                ));
            }
        }
    }
    selected
}

fn dependency_aliases(
    id: &str,
    packages: &HashMap<String, Value>,
    resolve: &HashMap<String, Value>,
    manifest_packages: &HashMap<PathBuf, &String>,
) -> Vec<(String, String)> {
    if let Some(deps) = resolve.get(id) {
        return array(deps)
            .filter_map(|dependency| {
                // Build dependencies are not in the ordinary source extern prelude.
                if array(&dependency["dep_kinds"]).all(|kind| kind["kind"] == "build") {
                    return None;
                }
                Some((
                    dependency["name"].as_str()?.to_owned(),
                    dependency["pkg"].as_str()?.to_owned(),
                ))
            })
            .collect();
    }
    // --no-deps still supplies workspace path declarations.
    declared_path_dependencies(&packages[id], manifest_packages)
}

fn declared_path_dependencies(
    package: &Value,
    manifest_packages: &HashMap<PathBuf, &String>,
) -> Vec<(String, String)> {
    array(&package["dependencies"])
        .filter_map(|dependency| {
            if dependency["kind"] == "build" {
                return None;
            }
            let path = dependency["path"].as_str()?;
            let package_id =
                manifest_packages.get(&absolute(&Path::new(path).join("Cargo.toml")))?;
            let alias = dependency["rename"]
                .as_str()
                .or_else(|| dependency["name"].as_str())?;
            Some((alias.replace('-', "_"), (*package_id).clone()))
        })
        .collect()
}

fn dependency_bindings(
    id: &str,
    package: &Value,
    library: bool,
    aliases: Vec<(String, String)>,
    libraries: &HashMap<String, String>,
) -> HashMap<String, String> {
    let mut bindings = HashMap::new();
    let mut ambiguous = HashSet::new();
    for (alias, package_id) in aliases {
        let Some(root) = libraries.get(&package_id) else {
            continue;
        };
        if bindings
            .get(&alias)
            .is_some_and(|previous| previous != root)
        {
            ambiguous.insert(alias.clone());
        }
        bindings.insert(alias, root.clone());
    }
    // A package's binary/test/example targets can refer to its own library.
    if !library {
        if let Some(root) = libraries.get(id) {
            if let Some(lib) = array(&package["targets"])
                .find(|target| array(&target["kind"]).any(|kind| kind == "lib"))
            {
                if let Some(name) = lib["name"].as_str() {
                    bindings.insert(name.replace('-', "_"), root.clone());
                }
            }
        }
    }
    for alias in ambiguous {
        bindings.remove(&alias);
    }
    bindings
}

fn array(value: &Value) -> impl Iterator<Item = &Value> {
    value.as_array().into_iter().flatten()
}

fn cargo_metadata(manifest: &Path, no_deps: bool) -> Result<Value, String> {
    let mut command = Command::new("cargo");
    command
        .args([
            "metadata",
            "--format-version=1",
            "--offline",
            "--locked",
            "--manifest-path",
        ])
        .arg(manifest)
        .current_dir(manifest.parent().unwrap_or(Path::new(".")))
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    if no_deps {
        command.arg("--no-deps");
    }
    let mut child = command.spawn().map_err(|error| error.to_string())?;
    let stdout = child.stdout.take().ok_or("Cargo stdout unavailable")?;
    let stderr = child.stderr.take().ok_or("Cargo stderr unavailable")?;
    // Drain both pipes while waiting so a large dependency graph cannot deadlock.
    let output = std::thread::spawn(move || {
        let mut bytes = Vec::new();
        stdout
            .take(MAX_METADATA_BYTES + 1)
            .read_to_end(&mut bytes)
            .map(|_| bytes)
    });
    let errors = std::thread::spawn(move || {
        let mut bytes = Vec::new();
        stderr.take(65536).read_to_end(&mut bytes).map(|_| bytes)
    });
    let started = Instant::now();
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break Ok(status),
            Ok(None) if started.elapsed() < Duration::from_secs(60) => {
                std::thread::sleep(Duration::from_millis(20))
            }
            Ok(None) => {
                let _ = child.kill();
                let _ = child.wait();
                break Err("Cargo metadata timed out".to_owned());
            }
            Err(error) => {
                let _ = child.kill();
                let _ = child.wait();
                break Err(error.to_string());
            }
        }
    };
    let bytes = output
        .join()
        .map_err(|_| "Cargo output reader failed")?
        .map_err(|e| e.to_string())?;
    let errors = errors
        .join()
        .map_err(|_| "Cargo error reader failed")?
        .map_err(|e| e.to_string())?;
    let status = status?;
    if !status.success() {
        return Err(String::from_utf8_lossy(&errors).trim().to_owned());
    }
    if bytes.len() as u64 > MAX_METADATA_BYTES {
        return Err("Cargo metadata exceeds the size limit".to_owned());
    }
    serde_json::from_slice(&bytes).map_err(|error| error.to_string())
}

struct Span {
    virtual_start: usize,
    original_start: usize,
    length: usize,
    path: PathBuf,
}
struct Builder {
    text: String,
    spans: Vec<Span>,
    sources: HashMap<PathBuf, Arc<String>>,
    loaded: HashSet<PathBuf>,
    active: HashSet<PathBuf>,
    warnings: Vec<String>,
}

impl Builder {
    fn load(&mut self, path: &Path, module_dir: &Path, depth: usize) -> bool {
        let path = absolute(path);
        if depth > MAX_MODULE_DEPTH || self.text.len() >= MAX_PROJECT_BYTES {
            self.warnings.push(format!(
                "Project source limit reached at {}",
                path.display()
            ));
            return false;
        }
        if !self.active.insert(path.clone()) {
            self.warnings
                .push(format!("Cyclic module path at {}", path.display()));
            return false;
        }
        let source = match self
            .sources
            .get(&path)
            .cloned()
            .map(Ok)
            .unwrap_or_else(|| fs::read_to_string(&path).map(Arc::new))
        {
            Ok(source) => source,
            Err(error) => {
                self.active.remove(&path);
                self.warnings
                    .push(format!("Cannot read module {}: {error}", path.display()));
                return false;
            }
        };
        if self.text.len() + source.len() > MAX_PROJECT_BYTES {
            self.active.remove(&path);
            self.warnings.push(format!(
                "Project source limit reached at {}",
                path.display()
            ));
            return false;
        }
        let tree = match parse_rust_code(&source) {
            Ok(tree) => tree,
            Err(error) => {
                self.active.remove(&path);
                self.warnings
                    .push(format!("Cannot parse module {}: {error:?}", path.display()));
                return false;
            }
        };
        self.sources.insert(path.clone(), source.clone());
        self.loaded.insert(path.clone());
        let attribute_dir = path.parent().unwrap_or(Path::new("."));
        // A root shebang is not valid inside a synthetic module body.
        let start = if source.starts_with("#!") && !source.starts_with("#![") {
            source.find('\n').unwrap_or(source.len())
        } else {
            0
        };
        self.render(
            tree.root_node(),
            &path,
            &source,
            start,
            source.len(),
            module_dir,
            attribute_dir,
            depth,
        );
        self.active.remove(&path);
        true
    }

    #[allow(clippy::too_many_arguments)]
    fn render(
        &mut self,
        node: Node<'_>,
        path: &Path,
        source: &str,
        start: usize,
        end: usize,
        module_dir: &Path,
        attribute_dir: &Path,
        depth: usize,
    ) {
        let mut modules = Vec::new();
        collect_modules(node, &mut modules);
        modules.sort_by_key(|n| n.start_byte());
        let mut cursor = start;
        for module in modules {
            if module.start_byte() < start || module.end_byte() > end {
                continue;
            }
            let Some(name) = module.child_by_field_name("name") else {
                continue;
            };
            let name = source[name.byte_range()].trim_start_matches("r#");
            let attribute = module_path(module, source);
            if let Some(body) = module.child_by_field_name("body") {
                self.copy(path, source, cursor, body.start_byte() + 1);
                let directory = match attribute {
                    Some(Ok(p)) => attribute_dir.join(p),
                    _ => module_dir.join(name),
                };
                self.render(
                    body,
                    path,
                    source,
                    body.start_byte() + 1,
                    body.end_byte() - 1,
                    &directory,
                    &directory,
                    depth + 1,
                );
                cursor = body.end_byte() - 1;
            } else {
                let module_path = match attribute {
                    Some(Ok(p)) => Some(attribute_dir.join(p)),
                    Some(Err(error)) => {
                        self.warnings.push(format!(
                            "Unsupported module path in {}: {error}",
                            path.display()
                        ));
                        None
                    }
                    None => {
                        let direct = module_dir.join(format!("{name}.rs"));
                        let directory = module_dir.join(name).join("mod.rs");
                        match (self.exists(&direct), self.exists(&directory)) {
                            (true, false) => Some(direct),
                            (false, true) => Some(directory),
                            (true, true) => {
                                self.warnings
                                    .push(format!("Ambiguous module {name} in {}", path.display()));
                                None
                            }
                            _ => {
                                self.warnings.push(format!(
                                    "Module {name} source unavailable in {}",
                                    path.display()
                                ));
                                None
                            }
                        }
                    }
                };
                self.copy(path, source, cursor, module.end_byte() - 1);
                self.text.push('{');
                if let Some(child) = module_path {
                    let child = absolute(&child);
                    let directory = if child.file_name().is_some_and(|n| n == "mod.rs") {
                        child.parent().unwrap_or(Path::new(".")).to_path_buf()
                    } else {
                        child.with_extension("")
                    };
                    self.text.push('\n');
                    self.load(&child, &directory, depth + 1);
                    self.text.push('\n');
                }
                self.text.push('}');
                cursor = module.end_byte();
            }
        }
        self.copy(path, source, cursor, end);
    }

    fn exists(&self, path: &Path) -> bool {
        self.sources.contains_key(&absolute(path)) || path.is_file()
    }
    fn copy(&mut self, path: &Path, source: &str, start: usize, end: usize) {
        if start >= end {
            return;
        }
        self.spans.push(Span {
            virtual_start: self.text.len(),
            original_start: start,
            length: end - start,
            path: path.to_path_buf(),
        });
        self.text.push_str(&source[start..end]);
    }
}

fn collect_modules<'tree>(node: Node<'tree>, modules: &mut Vec<Node<'tree>>) {
    for index in 0..node.named_child_count() {
        let Some(child) = node.named_child(index as u32) else {
            continue;
        };
        if child.kind() == "mod_item" {
            modules.push(child);
        } else {
            collect_modules(child, modules);
        }
    }
}

fn module_path(module: Node<'_>, source: &str) -> Option<Result<PathBuf, String>> {
    let mut sibling = module.prev_named_sibling();
    while let Some(node) = sibling {
        if node.kind() != "attribute_item" {
            break;
        }
        if let Some(attribute) = node.named_child(0) {
            let name = attribute.named_child(0).map(|n| &source[n.byte_range()]);
            if name == Some("path") {
                let Some(value) = attribute.child_by_field_name("value") else {
                    return Some(Err("missing path value".to_owned()));
                };
                let text = &source[value.byte_range()];
                if text.starts_with('r') {
                    let start = text.find('"')?;
                    let end = text.rfind('"')?;
                    return Some(Ok(PathBuf::from(&text[start + 1..end])));
                }
                return Some(
                    serde_json::from_str::<String>(text)
                        .map(PathBuf::from)
                        .map_err(|e| e.to_string()),
                );
            }
            if name == Some("cfg_attr") && source[node.byte_range()].contains("path") {
                return Some(Err(
                    "conditional path attributes need cfg evaluation".to_owned()
                ));
            }
        }
        sibling = node.prev_named_sibling();
    }
    None
}

fn absolute(path: &Path) -> PathBuf {
    path.canonicalize().unwrap_or_else(|_| {
        let path = if path.is_absolute() {
            path.to_path_buf()
        } else {
            std::env::current_dir().unwrap_or_default().join(path)
        };
        let mut normalized = PathBuf::new();
        for component in path.components() {
            if component == std::path::Component::ParentDir {
                normalized.pop();
            } else if component != std::path::Component::CurDir {
                normalized.push(component.as_os_str());
            }
        }
        normalized
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::analyze::analyze_project_file;

    type TestCrate<'a> = (&'a str, &'a str, u16, &'a [(&'a str, &'a str)]);

    fn virtual_project(files: &[(&str, &str)], crates: &[TestCrate<'_>]) -> Project {
        let roots = crates
            .iter()
            .map(|(name, root, edition, deps)| Crate {
                name: (*name).to_owned(),
                root: PathBuf::from(root),
                edition: *edition,
                dependencies: deps
                    .iter()
                    .map(|(alias, target)| ((*alias).to_owned(), (*target).to_owned()))
                    .collect(),
            })
            .collect();
        let overrides = files
            .iter()
            .map(|(path, source)| ((*path).to_owned(), (*source).to_owned()))
            .collect();
        let (project, warnings) = Project::from_crates(roots, overrides, Vec::new());
        assert!(warnings.is_empty(), "{warnings:?}");
        project
    }

    fn count(project: &Project, path: &str, source: &str) -> usize {
        project.ranges(path, source).expect("indexed file").len()
    }

    #[test]
    fn separate_files_and_mod_rs_share_a_crate_root() {
        let files = [
            (
                "/virtual/lib.rs",
                "mod a; mod b; fn entry() { a::first(); }",
            ),
            ("/virtual/a.rs", "pub fn first() { crate::b::second(); }"),
            (
                "/virtual/b/mod.rs",
                "pub fn second() { crate::a::first(); }",
            ),
        ];
        let project = virtual_project(&files, &[("local", files[0].0, 2021, &[])]);
        assert_eq!(count(&project, files[0].0, files[0].1), 0);
        assert_eq!(count(&project, files[1].0, files[1].1), 1);
        assert_eq!(count(&project, files[2].0, files[2].1), 1);
    }

    #[test]
    fn nested_files_and_inline_modules_use_their_module_directories() {
        for root in [
            "mod a;",
            "mod a { pub fn first() { inner::second(); } mod inner; }",
        ] {
            let files = [
                ("/virtual/lib.rs", root),
                (
                    "/virtual/a.rs",
                    "pub fn first() { inner::second(); } mod inner;",
                ),
                ("/virtual/a/inner.rs", "pub fn second() { super::first(); }"),
            ];
            let project = virtual_project(&files, &[("local", files[0].0, 2021, &[])]);
            assert_eq!(count(&project, files[2].0, files[2].1), 1);
            let file = if root == "mod a;" { files[1] } else { files[0] };
            assert_eq!(count(&project, file.0, file.1), 1);
        }
    }

    #[test]
    fn explicit_paths_and_unicode_offsets_map_to_original_files() {
        let files = [
            (
                "/virtual/lib.rs",
                "#[path = r#\"custom/source.rs\"#] mod a;",
            ),
            (
                "/virtual/custom/source.rs",
                "// ©\nmod next; pub fn récurse() { next::second(); }",
            ),
            (
                "/virtual/custom/source/next.rs",
                "pub fn second() { super::récurse(); }",
            ),
        ];
        let project = virtual_project(&files, &[("local", files[0].0, 2021, &[])]);
        let parameters = HashMap::from([("S3776:threshold".to_owned(), "0".to_owned())]);
        for file in &files[1..] {
            let output =
                analyze_project_file(file.1, &parameters, project.ranges(file.0, file.1)).unwrap();
            assert_eq!(output.metrics.cognitive_complexity, 1);
            assert_eq!(output.issues.len(), 1);
            assert_eq!(output.issues[0].secondary_locations.len(), 1);
        }
        let output = analyze_project_file(
            files[2].1,
            &parameters,
            project.ranges(files[2].0, files[2].1),
        )
        .unwrap();
        let location = &output.issues[0].secondary_locations[0].location;
        assert_eq!(location.start_line, 1);
        assert_eq!(location.end_column - location.start_column, 7);
    }

    #[test]
    fn external_receiver_types_and_absolute_extern_paths_resolve() {
        let files = [
            ("/virtual/app/lib.rs", "trait Hop { fn hop(&self); } impl Hop for vendor::Engine { fn hop(&self) { helper(); } } fn helper() { let e = ::vendor::Engine::new(); e.hop(); }"),
            ("/virtual/dep/lib.rs", "pub struct Engine; impl Engine { pub fn new() -> Self { Self } }"),
        ];
        let project = virtual_project(
            &files,
            &[
                ("app", files[0].0, 2021, &[("vendor", "dep")]),
                ("dep", files[1].0, 2021, &[]),
            ],
        );
        assert_eq!(count(&project, files[0].0, files[0].1), 2);
        assert_eq!(count(&project, files[1].0, files[1].1), 0);
    }

    #[test]
    fn dependency_identity_prevents_matching_unrelated_types() {
        let files = [
            ("/virtual/app/lib.rs", "trait Hop { fn hop(&self); } impl Hop for one::Engine { fn hop(&self) { helper(); } } fn helper() { let e = two::Engine::new(); e.hop(); }"),
            ("/virtual/one/lib.rs", "pub struct Engine; impl Engine { pub fn new() -> Self { Self } }"),
            ("/virtual/two/lib.rs", "pub struct Engine; impl Engine { pub fn new() -> Self { Self } }"),
        ];
        let project = virtual_project(
            &files,
            &[
                ("app", files[0].0, 2021, &[("one", "one"), ("two", "two")]),
                ("one", files[1].0, 2021, &[]),
                ("two", files[2].0, 2021, &[]),
            ],
        );
        assert_eq!(count(&project, files[0].0, files[0].1), 0);
    }

    #[test]
    fn legacy_extern_crate_alias_and_root_isolation() {
        let files = [
            ("/virtual/app/lib.rs", "extern crate vendor as old; trait Hop { fn hop(&self); } impl Hop for old::Engine { fn hop(&self) { helper(); } } fn helper() { let e = old::Engine::new(); e.hop(); } fn escape() { super::app::escape(); }"),
            ("/virtual/dep/lib.rs", "pub struct Engine; impl Engine { pub fn new() -> Self { Self } }"),
        ];
        let project = virtual_project(
            &files,
            &[
                ("app", files[0].0, 2015, &[("vendor", "dep")]),
                ("dep", files[1].0, 2021, &[]),
            ],
        );
        assert_eq!(count(&project, files[0].0, files[0].1), 2);
    }

    #[test]
    fn changed_or_unindexed_snapshots_do_not_use_old_ranges() {
        let files = [("/virtual/lib.rs", "fn a() { a(); }")];
        let project = virtual_project(&files, &[("local", files[0].0, 2021, &[])]);
        assert!(project.ranges(files[0].0, "fn a() {}").is_none());
        assert!(project.ranges("/virtual/other.rs", files[0].1).is_none());
    }

    #[test]
    fn cargo_workspace_renamed_dependency_and_original_file_issues() {
        let manifest =
            Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/project/Cargo.toml");
        let (project, warnings) =
            Project::load(&[manifest.to_string_lossy().into_owned()], HashMap::new());
        assert!(warnings.is_empty(), "{warnings:?}");
        let base = manifest.parent().unwrap();
        for (path, expected) in [
            ("app/src/lib.rs", 0),
            ("app/src/receiver.rs", 2),
            ("app/src/custom/first.rs", 1),
            ("app/src/custom/second.rs", 1),
            ("app/src/main.rs", 0),
            ("dep/src/lib.rs", 0),
        ] {
            let path = base.join(path);
            let source = fs::read_to_string(&path).unwrap();
            assert_eq!(
                count(&project, &path.to_string_lossy(), &source),
                expected,
                "{}",
                path.display()
            );
        }
    }

    #[test]
    fn cached_registry_crate_constructor_resolves_receiver_type() {
        let manifest =
            Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/registry/Cargo.toml");
        let (project, warnings) =
            Project::load(&[manifest.to_string_lossy().into_owned()], HashMap::new());
        assert!(warnings.is_empty(), "{warnings:?}");
        let path = manifest.parent().unwrap().join("src/lib.rs");
        let source = fs::read_to_string(&path).unwrap();
        assert_eq!(count(&project, &path.to_string_lossy(), &source), 2);
    }

    #[test]
    fn missing_metadata_degrades_without_losing_standalone_analysis() {
        let (project, warnings) = Project::load(
            &["/nonexistent/sonar-rust/Cargo.toml".to_owned()],
            HashMap::new(),
        );
        assert!(!warnings.is_empty());
        assert!(project.files.is_empty());
        assert!(project
            .ranges("/nonexistent/lib.rs", "fn a() { a(); }")
            .is_none());
    }
}
