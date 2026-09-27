mod world;
mod options;
mod source_map;
mod regions;

use serde::Serialize;
use typst::diag::{Severity, SourceDiagnostic};
use typst::{World, WorldExt};
#[cfg(feature = "browser")]
mod browser;
#[cfg(feature = "raw")]
mod raw;
use world::{file_id, MemoryWorld, MissingFile};
use options::{CompileOptions, error};
use source_map::{DocumentSnapshot, line_column};

#[cfg(not(typst_v15))]
use typst::layout::PagedDocument;
#[cfg(typst_v15)]
use typst_layout::PagedDocument;

pub fn version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Diagnostic {
    severity: &'static str,
    message: String,
    path: Option<String>,
    byte_start: Option<usize>,
    byte_end: Option<usize>,
    utf16_start: Option<usize>,
    utf16_end: Option<usize>,
    hints: Vec<String>,
    start_line: Option<usize>,
    start_column: Option<usize>,
    end_line: Option<usize>,
    end_column: Option<usize>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CompileOutput {
    typst_version: String,
    is_success: bool,
    page_count: usize,
    diagnostics: Vec<Diagnostic>,
    svg_pages: Vec<String>,
    regions: Vec<Vec<regions::SemanticRegion>>,
    png_pages: Vec<serde_bytes::ByteBuf>,
    html: String,
    pages: Vec<PageInfo>,
    missing_files: Vec<MissingFile>,
    has_source_map: bool,
    #[serde(with = "serde_bytes")]
    pdf: Vec<u8>,
}

#[derive(Serialize)]
struct PageInfo {
    number: usize,
    width: f64,
    height: f64,
}

struct Compiler {
    world: MemoryWorld,
    document: Option<DocumentSnapshot>,
}

impl Compiler {
    fn new() -> Self { Self { world: MemoryWorld::new(), document: None } }
}

impl Compiler {
    fn compile_document(&mut self, main_path: &str, timestamp_millis: f64, utc_offset_minutes: i32, mut options: CompileOptions) -> Result<CompileOutput, String> {
        options.validate()?;
        let ranges = options.page_ranges()?;
        self.world.set_clock(timestamp_millis, utc_offset_minutes)?;
        self.world.main = file_id(None, main_path)?;
        if options.format == "html" && !options.features.iter().any(|name| name == "html") {
            options.features.push("html".into());
        }
        self.world.set_features(&options.features)?;
        self.world.begin_compile();
        let mut output = CompileOutput {
            typst_version: version(), is_success: false, page_count: 0, diagnostics: Vec::new(),
            svg_pages: Vec::new(), regions: Vec::new(), png_pages: Vec::new(), html: String::new(), pages: Vec::new(),
            pdf: Vec::new(), missing_files: Vec::new(), has_source_map: false,
        };
        if options.format == "html" {
            self.compile_html(&options, &mut output);
        } else {
            let compiled = typst::compile::<PagedDocument>(&self.world);
            output.diagnostics.extend(compiled.warnings.iter().map(|item| self.diagnostic(item)));
            match compiled.output {
                Ok(document) => {
                    #[cfg(not(typst_v15))]
                    let pages = &document.pages;
                    #[cfg(typst_v15)]
                    let pages = document.pages();
                    let selected: Vec<_> = pages.iter().enumerate().filter(|(index, _)| ranges.as_ref().is_none_or(|ranges| ranges.includes_page_index(*index))).collect();
                    if selected.is_empty() { return Err(error("Selected page range contains no pages")) }
                    output.pages = selected.iter().map(|(index, page)| PageInfo {
                        number: index + 1, width: page.frame.size().x.to_pt(), height: page.frame.size().y.to_pt(),
                    }).collect();
                    output.page_count = selected.len();
                    if options.format == "pdf" {
                        let mut pdf_options = typst_pdf::PdfOptions::default();
                        pdf_options.page_ranges = ranges.clone();
                        pdf_options.standards = typst_pdf::PdfStandards::new(&options.pdf_standards).map_err(|failure| error(&format!("{failure:?}")))?;
                        pdf_options.timestamp = options.creation_timestamp_millis.map(pdf_timestamp).transpose()?;
                        #[cfg(typst_v14)]
                        { pdf_options.tagged = options.should_tag_pdf; }
                        #[cfg(typst_v15)]
                        { pdf_options.pretty = options.should_pretty_print; }
                        match typst_pdf::pdf(&document, &pdf_options) {
                            Ok(pdf) => { output.pdf = pdf; output.is_success = true; }
                            Err(errors) => output.diagnostics.extend(errors.iter().map(|item| self.diagnostic(item))),
                        }
                    } else if options.format == "svg" {
                        output.regions = selected.iter().map(|(_, page)| regions::semantic_regions(&document, &page.frame)).collect();
                        output.svg_pages = selected.iter().map(|(_, page)| {
                            #[cfg(not(typst_v15))]
                            { typst_svg::svg(page) }
                            #[cfg(typst_v15)]
                            { typst_svg::svg(page, &typst_svg::SvgOptions::default()) }
                        }).collect();
                        output.is_success = true;
                    } else if options.format == "png" {
                        for (_, page) in selected {
                            let scale = options.ppi / 72.0;
                            let width = (page.frame.size().x.to_pt() * scale).round().max(1.0);
                            let height = (page.frame.size().y.to_pt() * scale).round().max(1.0);
                            if !width.is_finite() || !height.is_finite() || width * height > (u32::MAX as f64 / 4.0) {
                                return Err(error("PNG exceeds WebAssembly address space; reduce ppi or page size"));
                            }
                            #[cfg(not(typst_v15))]
                            let image = typst_render::render(page, scale as f32);
                            #[cfg(typst_v15)]
                            let image = typst_render::render(page, &typst_render::RenderOptions {
                                pixel_per_pt: typst::utils::Scalar::new(scale), render_bleed: options.should_render_bleed,
                            });
                            output.png_pages.push(serde_bytes::ByteBuf::from(image.encode_png().map_err(|failure| error(&failure.to_string()))?));
                        }
                        output.is_success = true;
                    } else {
                        output.is_success = true;
                    }
                    if output.is_success && options.should_retain_source_map {
                        self.document = Some(DocumentSnapshot { world: self.world.clone(), document });
                        output.has_source_map = true;
                    }
                }
                Err(errors) => output.diagnostics.extend(errors.iter().map(|item| self.diagnostic(item))),
            }
        }
        output.missing_files = self.world.missing_files();
        // Keep recent incremental results without retaining every edit for the instance lifetime.
        comemo::evict(10);
        Ok(output)
    }

    fn compile_html(&self, _options: &CompileOptions, output: &mut CompileOutput) {
        #[cfg(not(typst_v14))]
        type HtmlDocument = typst::html::HtmlDocument;
        #[cfg(typst_v14)]
        type HtmlDocument = typst_html::HtmlDocument;
        let compiled = typst::compile::<HtmlDocument>(&self.world);
        output.diagnostics.extend(compiled.warnings.iter().map(|item| self.diagnostic(item)));
        match compiled.output {
            Ok(document) => {
                #[cfg(not(typst_v15))]
                let encoded = typst_html::html(&document);
                #[cfg(typst_v15)]
                let encoded = typst_html::html(&document, &typst_html::HtmlOptions { pretty: _options.should_pretty_print });
                match encoded {
                    Ok(html) => { output.html = html; output.is_success = true; }
                    Err(errors) => output.diagnostics.extend(errors.iter().map(|item| self.diagnostic(item))),
                }
            }
            Err(errors) => output.diagnostics.extend(errors.iter().map(|item| self.diagnostic(item))),
        }
    }

    fn diagnostic(&self, item: &SourceDiagnostic) -> Diagnostic {
        let id = item.span.id();
        let range = self.world.range(item.span);
        let source = id.and_then(|id| self.world.source(id).ok());
        let utf16_offset = |offset: usize| source.as_ref()
            .and_then(|source| source.text().get(..offset))
            .map(|text| text.encode_utf16().count());
        let start = source.as_ref().and_then(|source| range.as_ref().and_then(|range| line_column(source.text(), range.start)));
        let end = source.as_ref().and_then(|source| range.as_ref().and_then(|range| line_column(source.text(), range.end)));
        Diagnostic {
            start_line: start.map(|point| point.0), start_column: start.map(|point| point.1),
            end_line: end.map(|point| point.0), end_column: end.map(|point| point.1),
            severity: match item.severity { Severity::Error => "error", Severity::Warning => "warning" },
            message: item.message.to_string(),
            path: id.map(world::display_path),
            byte_start: range.as_ref().map(|range| range.start),
            byte_end: range.as_ref().map(|range| range.end),
            utf16_start: range.as_ref().and_then(|range| utf16_offset(range.start)),
            utf16_end: range.as_ref().and_then(|range| utf16_offset(range.end)),
            hints: item.hints.iter().map(|hint| {
                #[cfg(not(typst_v15))]
                { hint.to_string() }
                #[cfg(typst_v15)]
                { hint.v.to_string() }
            }).collect(),
        }
    }
}

fn pdf_timestamp(timestamp_millis: f64) -> Result<typst_pdf::Timestamp, String> {
    if !timestamp_millis.is_finite() { return Err(error("Invalid creation timestamp")) }
    let date = time::OffsetDateTime::from_unix_timestamp((timestamp_millis / 1000.0).floor() as i64).map_err(|failure| error(&failure.to_string()))?;
    let datetime = typst::foundations::Datetime::from_ymd_hms(date.year(), date.month() as u8, date.day(), date.hour(), date.minute(), date.second())
        .ok_or_else(|| error("Invalid creation timestamp"))?;
    Ok(typst_pdf::Timestamp::new_utc(datetime))
}
