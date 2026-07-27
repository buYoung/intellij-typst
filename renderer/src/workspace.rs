use crate::preview::PreviewServer;
use crate::protocol::InitializeParams;
use serde::Serialize;
use std::num::NonZeroUsize;
use std::path::{Path, PathBuf};
use tinymist_world::args::{CompileFontArgs, CompileOnceArgs, CompilePackageArgs};
use tinymist_world::{ShadowApi, TypstSystemUniverse, TypstSystemWorld};
use typst::foundations::Bytes;
use typst::introspection::PagedPosition;
use typst::layout::{Abs, Point};
use typst::{World, WorldExt};
use typst_ide::{jump_from_click, IdeWorld, Jump};
use typst_layout::PagedDocument;
use url::Url;

pub struct Workspace {
    root: PathBuf,
    package_cache_path: Option<String>,
    preview: PreviewServer,
    latest_generation: u64,
    universe: TypstSystemUniverse,
    latest_document: Option<CompiledDocument>,
}

struct CompiledDocument {
    generation: u64,
    world: MappingWorld,
    document: PagedDocument,
}

struct MappingWorld(TypstSystemWorld);

impl World for MappingWorld {
    fn library(&self) -> &typst::utils::LazyHash<typst::Library> {
        self.0.library()
    }

    fn book(&self) -> &typst::utils::LazyHash<typst::text::FontBook> {
        self.0.book()
    }

    fn main(&self) -> typst::syntax::FileId {
        self.0.main()
    }

    fn source(&self, id: typst::syntax::FileId) -> typst::diag::FileResult<typst::syntax::Source> {
        self.0.source(id)
    }

    fn file(&self, id: typst::syntax::FileId) -> typst::diag::FileResult<Bytes> {
        self.0.file(id)
    }

    fn font(&self, index: usize) -> Option<typst::text::Font> {
        self.0.font(index)
    }

    fn today(
        &self,
        offset: Option<typst::foundations::Duration>,
    ) -> Option<typst::foundations::Datetime> {
        self.0.today(offset)
    }
}

impl IdeWorld for MappingWorld {
    fn upcast(&self) -> &dyn World {
        self
    }
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

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DocumentSource {
    pub mapped: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub uri: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub line: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub column: Option<u32>,
}

impl DocumentSource {
    fn unmapped() -> Self {
        Self {
            mapped: false,
            uri: None,
            line: None,
            column: None,
        }
    }
}

impl Workspace {
    pub fn new(workspace_id: &str, params: InitializeParams) -> Result<Self, String> {
        let root = uri_to_path(&params.root_uri)?;
        let main = uri_to_path(&params.main_uri)?;
        if !main.starts_with(&root) {
            return Err("main file must be inside the configured root".to_owned());
        }
        let universe = create_universe(&root, &main, &params)?;
        Ok(Self {
            root,
            package_cache_path: params.package_cache_path,
            preview: PreviewServer::start(workspace_id).map_err(|error| error.to_string())?,
            latest_generation: 0,
            universe,
            latest_document: None,
        })
    }

    pub fn update_source(&mut self, uri: &str, text: Option<String>) -> Result<(), String> {
        let path = uri_to_path(uri)?;
        if !path.starts_with(&self.root) {
            return Err("source overlay must be inside the configured root".to_owned());
        }
        match text {
            Some(text) => {
                self.universe
                    .map_shadow(&path, Bytes::from_string(text))
                    .map_err(|error| error.to_string())?;
            }
            None => {
                self.universe
                    .unmap_shadow(&path)
                    .map_err(|error| error.to_string())?;
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
        let world = MappingWorld(self.universe.snapshot());
        let compiled = typst::compile::<PagedDocument>(&world);
        let mut diagnostics = compiled
            .warnings
            .iter()
            .filter_map(|diagnostic| native_diagnostic(&world, diagnostic))
            .collect::<Vec<_>>();
        let document = match compiled.output {
            Ok(document) => document,
            Err(errors) => {
                diagnostics.extend(
                    errors
                        .iter()
                        .filter_map(|diagnostic| native_diagnostic(&world, diagnostic)),
                );
                return Ok(CompileResult {
                    output_status: "failed",
                    diagnostics,
                    pages: Vec::new(),
                    source_mapping_available: false,
                    preview_url: None,
                });
            }
        };
        if render {
            let svg_options = typst_svg::SvgOptions::default();
            let svg_pages = document
                .pages()
                .iter()
                .map(|page| typst_svg::svg(page, &svg_options))
                .collect::<Vec<_>>();
            self.preview
                .update(&svg_pages)
                .map_err(|error| error.to_string())?;
        }
        let pages = if render {
            document
                .pages()
                .iter()
                .enumerate()
                .map(|(index, page)| Page {
                    number: index as u32 + 1,
                    width: Some(page.frame.size().x.to_pt()),
                    height: Some(page.frame.size().y.to_pt()),
                })
                .collect()
        } else {
            Vec::new()
        };
        if render {
            self.latest_document = Some(CompiledDocument {
                generation,
                world,
                document,
            });
        }
        Ok(CompileResult {
            output_status: "success",
            diagnostics,
            pages,
            source_mapping_available: render,
            preview_url: render.then(|| self.preview.url().to_owned()),
        })
    }

    pub fn document_to_source(
        &self,
        generation: u64,
        page: u32,
        x: f64,
        y: f64,
    ) -> Result<DocumentSource, String> {
        let Some(compiled) = self.latest_document.as_ref() else {
            return Ok(DocumentSource::unmapped());
        };
        if compiled.generation != generation {
            return Ok(DocumentSource::unmapped());
        }
        let page =
            NonZeroUsize::new(page as usize).ok_or_else(|| "page must start at 1".to_owned())?;
        let position = PagedPosition {
            page,
            point: Point::new(Abs::pt(x), Abs::pt(y)),
        };
        let Some(Jump::File(file_id, byte_offset)) =
            jump_from_click(&compiled.world, &compiled.document, &position)
        else {
            return Ok(DocumentSource::unmapped());
        };
        let source = compiled
            .world
            .source(file_id)
            .map_err(|error| error.to_string())?;
        let line = source
            .lines()
            .byte_to_line(byte_offset)
            .ok_or_else(|| "source byte offset is outside the file".to_owned())?;
        let utf16_offset = source
            .lines()
            .byte_to_utf16(byte_offset)
            .ok_or_else(|| "source byte offset is not a UTF-8 boundary".to_owned())?;
        let line_byte_offset = source
            .lines()
            .line_to_byte(line)
            .ok_or_else(|| "source line is outside the file".to_owned())?;
        let line_utf16_offset = source.lines().byte_to_utf16(line_byte_offset).unwrap_or(0);
        let path = compiled
            .world
            .0
            .path_for_id(file_id)
            .map_err(|error| error.to_string())?;
        Ok(DocumentSource {
            mapped: true,
            uri: Some(
                Url::from_file_path(path.as_path())
                    .map_err(|_| {
                        "mapped source path cannot be represented as a file URI".to_owned()
                    })?
                    .to_string(),
            ),
            line: Some(line as u32),
            column: Some((utf16_offset - line_utf16_offset) as u32),
        })
    }

    pub fn package_cache_root(&self) -> PathBuf {
        self.package_cache_path
            .as_deref()
            .filter(|path| !path.is_empty())
            .map(PathBuf::from)
            .unwrap_or_else(default_package_cache)
    }
}

fn create_universe(
    root: &Path,
    main: &Path,
    params: &InitializeParams,
) -> Result<TypstSystemUniverse, String> {
    let args = CompileOnceArgs {
        input: Some(main.to_string_lossy().into_owned()),
        root: Some(root.to_path_buf()),
        font: CompileFontArgs {
            font_paths: params.font_paths.iter().map(PathBuf::from).collect(),
            ignore_system_fonts: !params.use_system_fonts,
        },
        package: CompilePackageArgs {
            package_path: params
                .package_path
                .as_deref()
                .filter(|path| !path.is_empty())
                .map(PathBuf::from),
            package_cache_path: params
                .package_cache_path
                .as_deref()
                .filter(|path| !path.is_empty())
                .map(PathBuf::from),
        },
        ..CompileOnceArgs::default()
    };
    args.resolve_system().map_err(|error| error.to_string())
}

fn native_diagnostic(
    world: &MappingWorld,
    diagnostic: &typst::diag::SourceDiagnostic,
) -> Option<Diagnostic> {
    let file_id = diagnostic.span.id()?;
    let source = world.source(file_id).ok()?;
    let range = world.range(diagnostic.span)?;
    let start_line = source.lines().byte_to_line(range.start)?;
    let end_line = source.lines().byte_to_line(range.end)?;
    let start_line_byte = source.lines().line_to_byte(start_line)?;
    let end_line_byte = source.lines().line_to_byte(end_line)?;
    let start_column = source.lines().byte_to_utf16(range.start)?
        - source.lines().byte_to_utf16(start_line_byte)?;
    let end_column =
        source.lines().byte_to_utf16(range.end)? - source.lines().byte_to_utf16(end_line_byte)?;
    let path = world.0.path_for_id(file_id).ok()?;
    let uri = Url::from_file_path(path.as_path()).ok()?.to_string();
    Some(Diagnostic {
        severity: match diagnostic.severity {
            typst::diag::Severity::Error => "error",
            typst::diag::Severity::Warning => "warning",
        },
        message: diagnostic.message.to_string(),
        uri,
        start_line: start_line as u32,
        start_column: start_column as u32,
        end_line: end_line as u32,
        end_column: end_column as u32,
        trace: Vec::new(),
    })
}

#[cfg(test)]
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
