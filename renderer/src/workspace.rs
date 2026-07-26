use crate::preview::{find_svg_pages, PreviewServer};
use crate::protocol::InitializeParams;
use serde::Serialize;
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use tempfile::TempDir;
use url::Url;

pub struct Workspace {
    root: PathBuf,
    main: PathBuf,
    typst_executable: String,
    font_paths: Vec<String>,
    use_system_fonts: bool,
    package_path: Option<String>,
    package_cache_path: Option<String>,
    overlays: HashMap<PathBuf, String>,
    preview: PreviewServer,
    latest_generation: u64,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CompileResult {
    pub output_status: &'static str,
    pub diagnostics: Vec<Diagnostic>,
    pub pages: Vec<Page>,
    pub source_mapping_available: bool,
    pub preview_url: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Diagnostic {
    pub severity: &'static str,
    pub message: String,
    pub uri: String,
    pub start_line: u32,
    pub start_column: u32,
    pub end_line: u32,
    pub end_column: u32,
    pub trace: Vec<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Page {
    pub number: u32,
    pub width: Option<f64>,
    pub height: Option<f64>,
}

impl Workspace {
    pub fn new(workspace_id: &str, params: InitializeParams) -> Result<Self, String> {
        let root = uri_to_path(&params.root_uri)?;
        let main = uri_to_path(&params.main_uri)?;
        if !main.starts_with(&root) {
            return Err("main file must be inside the configured root".to_owned());
        }
        Ok(Self {
            root,
            main,
            typst_executable: params.typst_executable,
            font_paths: params.font_paths,
            use_system_fonts: params.use_system_fonts,
            package_path: params.package_path,
            package_cache_path: params.package_cache_path,
            overlays: HashMap::new(),
            preview: PreviewServer::start(workspace_id).map_err(|error| error.to_string())?,
            latest_generation: 0,
        })
    }

    pub fn update_source(&mut self, uri: &str, text: Option<String>) -> Result<(), String> {
        let path = uri_to_path(uri)?;
        if !path.starts_with(&self.root) {
            return Err("source overlay must be inside the configured root".to_owned());
        }
        match text {
            Some(text) => {
                self.overlays.insert(path, text);
            }
            None => {
                self.overlays.remove(&path);
            }
        }
        Ok(())
    }

    pub fn compile(&mut self, generation: u64, render: bool) -> Result<CompileResult, String> {
        if generation < self.latest_generation {
            return Ok(CompileResult {
                output_status: "cancelled",
                diagnostics: Vec::new(),
                pages: Vec::new(),
                source_mapping_available: false,
                preview_url: None,
            });
        }
        self.latest_generation = generation;
        let overlay = self.create_overlay()?;
        let compile_root = overlay.as_ref().map_or(self.root.as_path(), TempDir::path);
        let relative_main = self
            .main
            .strip_prefix(&self.root)
            .map_err(|error| error.to_string())?;
        let compile_main = compile_root.join(relative_main);
        let output_directory = tempfile::tempdir().map_err(|error| error.to_string())?;
        let output_pattern = output_directory.path().join("preview-{p}.svg");

        let mut command = Command::new(&self.typst_executable);
        command
            .current_dir(compile_root)
            .args(["compile", "--diagnostic-format", "short", "--root"])
            .arg(compile_root);
        for font_path in &self.font_paths {
            command.args(["--font-path", font_path]);
        }
        if !self.use_system_fonts {
            command.arg("--ignore-system-fonts");
        }
        if let Some(path) = self.package_path.as_deref().filter(|path| !path.is_empty()) {
            command.args(["--package-path", path]);
        }
        if let Some(path) = self
            .package_cache_path
            .as_deref()
            .filter(|path| !path.is_empty())
        {
            command.args(["--package-cache-path", path]);
        }
        command
            .args(["--format", "svg"])
            .arg(&compile_main)
            .arg(&output_pattern)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        let output = command.output().map_err(|error| error.to_string())?;
        if generation != self.latest_generation {
            return Ok(CompileResult {
                output_status: "cancelled",
                diagnostics: Vec::new(),
                pages: Vec::new(),
                source_mapping_available: false,
                preview_url: None,
            });
        }

        let stderr = String::from_utf8_lossy(&output.stderr);
        let diagnostics = parse_short_diagnostics(&stderr, compile_root, &self.root);
        let files = find_svg_pages(&output_pattern).map_err(|error| error.to_string())?;
        if output.status.success() && render {
            self.preview
                .update_from_files(&files)
                .map_err(|error| error.to_string())?;
        }
        let pages = files
            .iter()
            .enumerate()
            .map(|(index, _)| Page {
                number: index as u32 + 1,
                width: None,
                height: None,
            })
            .collect();
        Ok(CompileResult {
            output_status: if output.status.success() {
                "success"
            } else {
                "failed"
            },
            diagnostics,
            pages,
            source_mapping_available: false,
            preview_url: (output.status.success() && render).then(|| self.preview.url().to_owned()),
        })
    }

    pub fn package_cache_root(&self) -> PathBuf {
        self.package_cache_path
            .as_deref()
            .filter(|path| !path.is_empty())
            .map(PathBuf::from)
            .unwrap_or_else(default_package_cache)
    }

    fn create_overlay(&self) -> Result<Option<TempDir>, String> {
        if self.overlays.is_empty() {
            return Ok(None);
        }
        let directory = tempfile::tempdir().map_err(|error| error.to_string())?;
        mirror_tree(&self.root, directory.path())?;
        for (source, text) in &self.overlays {
            let relative = source
                .strip_prefix(&self.root)
                .map_err(|error| error.to_string())?;
            let destination = directory.path().join(relative);
            if let Some(parent) = destination.parent() {
                fs::create_dir_all(parent).map_err(|error| error.to_string())?;
            }
            fs::write(destination, text).map_err(|error| error.to_string())?;
        }
        Ok(Some(directory))
    }
}

fn mirror_tree(source: &Path, destination: &Path) -> Result<(), String> {
    fs::create_dir_all(destination).map_err(|error| error.to_string())?;
    for entry in fs::read_dir(source).map_err(|error| error.to_string())? {
        let entry = entry.map_err(|error| error.to_string())?;
        let source_path = entry.path();
        if source_path
            .file_name()
            .and_then(|name| name.to_str())
            .is_some_and(|name| name.starts_with(".typst-runtime-preview"))
        {
            continue;
        }
        let destination_path = destination.join(entry.file_name());
        if entry
            .file_type()
            .map_err(|error| error.to_string())?
            .is_dir()
        {
            mirror_tree(&source_path, &destination_path)?;
        } else {
            fs::copy(&source_path, &destination_path).map_err(|error| error.to_string())?;
        }
    }
    Ok(())
}

fn parse_short_diagnostics(
    stderr: &str,
    compile_root: &Path,
    original_root: &Path,
) -> Vec<Diagnostic> {
    stderr
        .lines()
        .filter_map(|line| {
            let (location, message) = line.split_once(": ")?;
            let mut parts = location.rsplitn(3, ':');
            let column = parts.next()?.parse::<u32>().ok()?.saturating_sub(1);
            let row = parts.next()?.parse::<u32>().ok()?.saturating_sub(1);
            let raw_path = parts.next()?;
            let compile_path = Path::new(raw_path);
            let absolute = if compile_path.is_absolute() {
                compile_path.to_owned()
            } else {
                compile_root.join(compile_path)
            };
            let original = absolute
                .strip_prefix(compile_root)
                .map(|relative| original_root.join(relative))
                .unwrap_or(absolute);
            let (severity, message) = if let Some(message) = message.strip_prefix("warning: ") {
                ("warning", message)
            } else if let Some(message) = message.strip_prefix("error: ") {
                ("error", message)
            } else {
                ("error", message)
            };
            Some(Diagnostic {
                severity,
                message: message.to_owned(),
                uri: Url::from_file_path(original).ok()?.to_string(),
                start_line: row,
                start_column: column,
                end_line: row,
                end_column: column.saturating_add(1),
                trace: Vec::new(),
            })
        })
        .collect()
}

fn uri_to_path(uri: &str) -> Result<PathBuf, String> {
    Url::parse(uri)
        .map_err(|error| error.to_string())?
        .to_file_path()
        .map_err(|_| format!("not a file URI: {uri}"))
}

fn default_package_cache() -> PathBuf {
    if cfg!(target_os = "windows") {
        std::env::var_os("LOCALAPPDATA")
            .map(PathBuf::from)
            .unwrap_or_else(std::env::temp_dir)
            .join("typst/packages")
    } else if cfg!(target_os = "macos") {
        std::env::var_os("HOME")
            .map(PathBuf::from)
            .unwrap_or_else(std::env::temp_dir)
            .join("Library/Caches/typst/packages")
    } else {
        std::env::var_os("XDG_CACHE_HOME")
            .map(PathBuf::from)
            .or_else(|| std::env::var_os("HOME").map(|home| PathBuf::from(home).join(".cache")))
            .unwrap_or_else(std::env::temp_dir)
            .join("typst/packages")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_unicode_diagnostic_position_as_zero_based() {
        let compile_root = if cfg!(windows) {
            Path::new(r"C:\overlay")
        } else {
            Path::new("/tmp/overlay")
        };
        let original_root = if cfg!(windows) {
            Path::new(r"C:\project")
        } else {
            Path::new("/project")
        };
        let diagnostics = parse_short_diagnostics(
            "main.typ:2:4: error: unknown variable",
            compile_root,
            original_root,
        );
        assert_eq!(diagnostics[0].start_line, 1);
        assert_eq!(diagnostics[0].start_column, 3);
        assert!(diagnostics[0].uri.ends_with("/project/main.typ"));
    }
}
