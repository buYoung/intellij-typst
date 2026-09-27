mod world;

use serde::Serialize;
use typst::diag::{Severity, SourceDiagnostic};
use typst::{World, WorldExt};
use wasm_bindgen::prelude::*;
use world::{file_id, MemoryWorld};

#[cfg(not(typst_v15))]
use typst::layout::PagedDocument;
#[cfg(typst_v15)]
use typst_layout::PagedDocument;

#[wasm_bindgen]
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
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CompileOutput {
    typst_version: String,
    is_success: bool,
    page_count: usize,
    diagnostics: Vec<Diagnostic>,
    svg_pages: Vec<String>,
    #[serde(with = "serde_bytes")]
    pdf: Vec<u8>,
}

#[wasm_bindgen]
pub struct Compiler {
    world: MemoryWorld,
}

#[wasm_bindgen]
impl Compiler {
    #[wasm_bindgen(constructor)]
    pub fn new() -> Self {
        console_error_panic_hook::set_once();
        Self { world: MemoryWorld::new() }
    }

    pub fn set_source(&mut self, path: &str, text: &str) -> Result<(), JsValue> {
        self.set_file(path, text.as_bytes())
    }

    pub fn set_file(&mut self, path: &str, data: &[u8]) -> Result<(), JsValue> {
        self.world.set_file(file_id(None, path)?, data);
        Ok(())
    }

    pub fn set_package_file(&mut self, specification: &str, path: &str, data: &[u8]) -> Result<(), JsValue> {
        self.world.set_file(file_id(Some(specification), path)?, data);
        Ok(())
    }

    pub fn remove_file(&mut self, path: &str) -> Result<(), JsValue> {
        self.world.remove_file(file_id(None, path)?);
        Ok(())
    }

    pub fn reset_files(&mut self) {
        self.world.reset_files();
    }

    pub fn add_font(&mut self, data: &[u8]) -> Result<(), JsValue> {
        self.world.add_font(data)
    }

    pub fn set_inputs(&mut self, inputs: JsValue) -> Result<(), JsValue> {
        let inputs = serde_wasm_bindgen::from_value(inputs)
            .map_err(|error| JsValue::from_str(&error.to_string()))?;
        self.world.set_inputs(inputs);
        Ok(())
    }

    pub fn compile(&mut self, main_path: &str, format: &str, timestamp_millis: f64, utc_offset_minutes: i32) -> Result<JsValue, JsValue> {
        if !matches!(format, "pdf" | "svg") {
            return Err(JsValue::from_str("Supported output formats: pdf, svg"));
        }
        self.world.set_clock(timestamp_millis, utc_offset_minutes)?;
        self.world.main = file_id(None, main_path)?;
        let compiled = typst::compile::<PagedDocument>(&self.world);
        let mut output = CompileOutput {
            typst_version: version(),
            is_success: false,
            page_count: 0,
            diagnostics: compiled.warnings.iter().map(|item| self.diagnostic(item)).collect(),
            svg_pages: Vec::new(),
            pdf: Vec::new(),
        };
        match compiled.output {
            Ok(document) => {
                #[cfg(not(typst_v15))]
                let pages = &document.pages;
                #[cfg(typst_v15)]
                let pages = document.pages();
                output.page_count = pages.len();
                if format == "pdf" {
                    match typst_pdf::pdf(&document, &typst_pdf::PdfOptions::default()) {
                        Ok(pdf) => { output.pdf = pdf; output.is_success = true; }
                        Err(errors) => output.diagnostics.extend(errors.iter().map(|item| self.diagnostic(item))),
                    }
                } else {
                    output.svg_pages = pages.iter().map(|page| {
                        #[cfg(not(typst_v15))]
                        { typst_svg::svg(page) }
                        #[cfg(typst_v15)]
                        { typst_svg::svg(page, &typst_svg::SvgOptions::default()) }
                    }).collect();
                    output.is_success = true;
                }
            }
            Err(errors) => output.diagnostics.extend(errors.iter().map(|item| self.diagnostic(item))),
        }
        serde_wasm_bindgen::to_value(&output).map_err(|error| JsValue::from_str(&error.to_string()))
    }
}

impl Compiler {
    fn diagnostic(&self, item: &SourceDiagnostic) -> Diagnostic {
        let id = item.span.id();
        let range = self.world.range(item.span);
        let source = id.and_then(|id| self.world.source(id).ok());
        let utf16_offset = |offset: usize| source.as_ref()
            .and_then(|source| source.text().get(..offset))
            .map(|text| text.encode_utf16().count());
        Diagnostic {
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
