use crate::preview::{PreviewServer, SemanticRegion};
use crate::protocol::InitializeParams;
use serde::Serialize;
use std::num::NonZeroUsize;
use std::path::{Path, PathBuf};
use tinymist_world::args::{CompileFontArgs, CompileOnceArgs, CompilePackageArgs};
use tinymist_world::{ShadowApi, TypstSystemUniverse, TypstSystemWorld};
use typst::foundations::{AsOutput, Bytes};
use typst::introspection::{DocumentPosition, PagedPosition};
use typst::layout::{Abs, Frame, FrameItem, Point, Rect, Transform};
use typst::model::Destination;
use typst::syntax::{LinkedNode, Side, Source};
use typst::text::{BottomEdge, BottomEdgeMetric, TextEdgeBounds, TopEdge, TopEdgeMetric};
use typst::{World, WorldExt};
use typst_ide::{jump_from_click, jump_from_cursor, IdeWorld, Jump};
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
    #[serde(skip_serializing_if = "Option::is_none")]
    pub end_line: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub end_column: Option<u32>,
}

impl DocumentSource {
    fn unmapped() -> Self {
        Self {
            mapped: false,
            uri: None,
            line: None,
            column: None,
            end_line: None,
            end_column: None,
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SourceDocument {
    pub mapped: bool,
    pub positions: Vec<DocumentPoint>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DocumentPoint {
    pub page: u32,
    pub x: f64,
    pub y: f64,
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
            let preview_pages = document
                .pages()
                .iter()
                .map(|page| {
                    (
                        typst_svg::svg(page, &svg_options),
                        page.frame.size().x.to_pt(),
                        page.frame.size().y.to_pt(),
                        semantic_regions(&document, &page.frame),
                    )
                })
                .collect::<Vec<_>>();
            self.preview
                .update(generation, preview_pages)
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
        let root = LinkedNode::new(source.root());
        let range = root
            .leaf_at(byte_offset, Side::After)
            .or_else(|| root.leaf_at(byte_offset, Side::Before))
            .map(|node| node.range())
            .unwrap_or(byte_offset..byte_offset);
        let (line, column) = utf16_line_column(&source, range.start)?;
        let (end_line, end_column) = utf16_line_column(&source, range.end)?;
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
            column: Some(column as u32),
            end_line: Some(end_line as u32),
            end_column: Some(end_column as u32),
        })
    }

    pub fn source_to_document(
        &self,
        generation: u64,
        uri: &str,
        line: u32,
        column: u32,
    ) -> Result<SourceDocument, String> {
        let Some(compiled) = self.latest_document.as_ref() else {
            return Ok(SourceDocument {
                mapped: false,
                positions: Vec::new(),
            });
        };
        if compiled.generation != generation {
            return Ok(SourceDocument {
                mapped: false,
                positions: Vec::new(),
            });
        }
        let path = uri_to_path(uri)?;
        if !path.starts_with(&self.root) {
            return Ok(SourceDocument {
                mapped: false,
                positions: Vec::new(),
            });
        }
        let Some(file_id) = compiled.world.0.id_for_path(&path) else {
            return Ok(SourceDocument {
                mapped: false,
                positions: Vec::new(),
            });
        };
        let source = compiled
            .world
            .source(file_id)
            .map_err(|error| error.to_string())?;
        let line_start = source
            .lines()
            .line_to_byte(line as usize)
            .ok_or_else(|| "source line is outside the file".to_owned())?;
        let line_start_utf16 = source.lines().byte_to_utf16(line_start).unwrap_or(0);
        let Some(cursor) = source
            .lines()
            .utf16_to_byte(line_start_utf16 + column as usize)
        else {
            return Ok(SourceDocument {
                mapped: false,
                positions: Vec::new(),
            });
        };
        let positions = jump_from_cursor(&compiled.document, &source, cursor)
            .into_iter()
            .map(|position| DocumentPoint {
                page: position.page.get() as u32,
                x: position.point.x.to_pt(),
                y: position.point.y.to_pt(),
            })
            .collect::<Vec<_>>();
        Ok(SourceDocument {
            mapped: !positions.is_empty(),
            positions,
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

fn utf16_line_column(source: &Source, byte_offset: usize) -> Result<(usize, usize), String> {
    let line = source
        .lines()
        .byte_to_line(byte_offset)
        .ok_or_else(|| "source byte offset is outside the file".to_owned())?;
    let utf16_offset = source
        .lines()
        .byte_to_utf16(byte_offset)
        .ok_or_else(|| "source byte offset is not a UTF-8 boundary".to_owned())?;
    let line_start = source
        .lines()
        .line_to_byte(line)
        .ok_or_else(|| "source line is outside the file".to_owned())?;
    let line_start_utf16 = source.lines().byte_to_utf16(line_start).unwrap_or(0);
    Ok((line, utf16_offset - line_start_utf16))
}

fn semantic_regions(document: &PagedDocument, frame: &Frame) -> Vec<SemanticRegion> {
    let mut regions = Vec::new();
    collect_regions(document, frame, Affine::identity(), None, &mut regions);
    regions.sort_by_key(|region| match region.kind {
        "shape" => 0,
        "text" => 1,
        "link" => 2,
        _ => 0,
    });
    regions
}

fn collect_regions(
    document: &PagedDocument,
    frame: &Frame,
    transform: Affine,
    clip: Option<Bounds>,
    regions: &mut Vec<SemanticRegion>,
) {
    for (position, item) in frame.items() {
        match item {
            FrameItem::Group(group) => {
                let translated =
                    transform.then(Affine::translation(position.x.to_pt(), position.y.to_pt()));
                let group_clip = group
                    .clip
                    .as_ref()
                    .map(|curve| transformed_bounds(curve.bbox(None), translated));
                let nested_clip = intersect_optional(clip, group_clip);
                collect_regions(
                    document,
                    &group.frame,
                    translated.then(Affine::from_transform(group.transform)),
                    nested_clip,
                    regions,
                );
            }
            FrameItem::Text(text) => {
                let mut cursor_x = position.x.to_pt();
                for glyph in &text.glyphs {
                    let glyph_x = cursor_x + glyph.x_offset.at(text.size).to_pt();
                    let glyph_y = position.y.to_pt() - glyph.y_offset.at(text.size).to_pt();
                    let (top, bottom) = text.font.edges(
                        TopEdge::Metric(TopEdgeMetric::Bounds),
                        BottomEdge::Metric(BottomEdgeMetric::Bounds),
                        text.size,
                        TextEdgeBounds::Glyph(glyph.id),
                    );
                    let rect = Bounds {
                        min_x: glyph_x,
                        min_y: glyph_y - top.to_pt(),
                        max_x: glyph_x + glyph.x_advance.at(text.size).to_pt(),
                        max_y: glyph_y + bottom.to_pt(),
                    };
                    push_region(regions, "text", rect.transformed(transform), clip, None);
                    cursor_x += glyph.x_advance.at(text.size).to_pt();
                }
            }
            FrameItem::Link(destination, size) => {
                let target = link_target(document, destination);
                push_region(
                    regions,
                    "link",
                    Bounds::from_position_size(*position, *size).transformed(transform),
                    clip,
                    Some(target),
                );
            }
            FrameItem::Shape(shape, _) => {
                let bounds = offset_rect(shape.bbox(true), *position);
                push_region(
                    regions,
                    "shape",
                    transformed_bounds(bounds, transform),
                    clip,
                    None,
                );
            }
            FrameItem::Image(_, size, _) => push_region(
                regions,
                "shape",
                Bounds::from_position_size(*position, *size).transformed(transform),
                clip,
                None,
            ),
            FrameItem::Tag(_) => {}
        }
    }
}

struct LinkTarget {
    url: Option<String>,
    position: Option<PagedPosition>,
}

fn link_target(document: &PagedDocument, destination: &Destination) -> LinkTarget {
    match destination {
        Destination::Url(url) => LinkTarget {
            url: Some(url.to_string()),
            position: None,
        },
        Destination::Position(position) => LinkTarget {
            url: None,
            position: Some(*position),
        },
        Destination::Location(location) => LinkTarget {
            url: None,
            position: document
                .as_output()
                .introspector()
                .position(*location)
                .and_then(|position| match position {
                    DocumentPosition::Paged(position) => Some(position),
                    _ => None,
                }),
        },
    }
}

fn push_region(
    regions: &mut Vec<SemanticRegion>,
    kind: &'static str,
    bounds: Bounds,
    clip: Option<Bounds>,
    target: Option<LinkTarget>,
) {
    let Some(bounds) = clip.map_or(Some(bounds), |clip| bounds.intersection(clip)) else {
        return;
    };
    if bounds.width() <= 0.1 || bounds.height() <= 0.1 || !bounds.is_finite() {
        return;
    }
    let position = target.as_ref().and_then(|target| target.position);
    regions.push(SemanticRegion {
        kind,
        x: bounds.min_x,
        y: bounds.min_y,
        width: bounds.width(),
        height: bounds.height(),
        url: target.and_then(|target| target.url),
        page: position.map(|position| position.page.get() as u32),
        target_x: position.map(|position| position.point.x.to_pt()),
        target_y: position.map(|position| position.point.y.to_pt()),
    });
}

#[derive(Clone, Copy)]
struct Affine {
    sx: f64,
    ky: f64,
    kx: f64,
    sy: f64,
    tx: f64,
    ty: f64,
}

impl Affine {
    fn identity() -> Self {
        Self {
            sx: 1.0,
            ky: 0.0,
            kx: 0.0,
            sy: 1.0,
            tx: 0.0,
            ty: 0.0,
        }
    }
    fn translation(tx: f64, ty: f64) -> Self {
        Self {
            tx,
            ty,
            ..Self::identity()
        }
    }
    fn from_transform(transform: Transform) -> Self {
        Self {
            sx: transform.sx.get(),
            ky: transform.ky.get(),
            kx: transform.kx.get(),
            sy: transform.sy.get(),
            tx: transform.tx.to_pt(),
            ty: transform.ty.to_pt(),
        }
    }
    fn then(self, next: Self) -> Self {
        Self {
            sx: self.sx * next.sx + self.kx * next.ky,
            ky: self.ky * next.sx + self.sy * next.ky,
            kx: self.sx * next.kx + self.kx * next.sy,
            sy: self.ky * next.kx + self.sy * next.sy,
            tx: self.sx * next.tx + self.kx * next.ty + self.tx,
            ty: self.ky * next.tx + self.sy * next.ty + self.ty,
        }
    }
    fn point(self, x: f64, y: f64) -> (f64, f64) {
        (
            self.sx * x + self.kx * y + self.tx,
            self.ky * x + self.sy * y + self.ty,
        )
    }
}

#[derive(Clone, Copy)]
struct Bounds {
    min_x: f64,
    min_y: f64,
    max_x: f64,
    max_y: f64,
}

impl Bounds {
    fn from_position_size(position: Point, size: typst::layout::Size) -> Self {
        Self {
            min_x: position.x.to_pt(),
            min_y: position.y.to_pt(),
            max_x: (position.x + size.x).to_pt(),
            max_y: (position.y + size.y).to_pt(),
        }
    }
    fn width(self) -> f64 {
        self.max_x - self.min_x
    }
    fn height(self) -> f64 {
        self.max_y - self.min_y
    }
    fn is_finite(self) -> bool {
        self.min_x.is_finite()
            && self.min_y.is_finite()
            && self.max_x.is_finite()
            && self.max_y.is_finite()
    }
    fn transformed(self, transform: Affine) -> Self {
        let points = [
            transform.point(self.min_x, self.min_y),
            transform.point(self.max_x, self.min_y),
            transform.point(self.max_x, self.max_y),
            transform.point(self.min_x, self.max_y),
        ];
        Self {
            min_x: points.iter().map(|p| p.0).fold(f64::INFINITY, f64::min),
            min_y: points.iter().map(|p| p.1).fold(f64::INFINITY, f64::min),
            max_x: points.iter().map(|p| p.0).fold(f64::NEG_INFINITY, f64::max),
            max_y: points.iter().map(|p| p.1).fold(f64::NEG_INFINITY, f64::max),
        }
    }
    fn intersection(self, other: Self) -> Option<Self> {
        let value = Self {
            min_x: self.min_x.max(other.min_x),
            min_y: self.min_y.max(other.min_y),
            max_x: self.max_x.min(other.max_x),
            max_y: self.max_y.min(other.max_y),
        };
        (value.min_x < value.max_x && value.min_y < value.max_y).then_some(value)
    }
}

fn offset_rect(rect: Rect, position: Point) -> Rect {
    Rect::new(rect.min + position, rect.max + position)
}
fn transformed_bounds(rect: Rect, transform: Affine) -> Bounds {
    Bounds {
        min_x: rect.min.x.to_pt(),
        min_y: rect.min.y.to_pt(),
        max_x: rect.max.x.to_pt(),
        max_y: rect.max.y.to_pt(),
    }
    .transformed(transform)
}
fn intersect_optional(first: Option<Bounds>, second: Option<Bounds>) -> Option<Bounds> {
    match (first, second) {
        (Some(first), Some(second)) => first.intersection(second),
        (Some(value), None) | (None, Some(value)) => Some(value),
        (None, None) => None,
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
