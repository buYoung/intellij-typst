use std::num::NonZeroUsize;
use serde::Deserialize;
use typst::layout::PageRanges;
use typst_pdf::PdfStandard;


#[derive(Deserialize)]
#[serde(default, rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct CompileOptions {
    pub format: String,
    pub ppi: f64,
    pub pages: Option<String>,
    pub pdf_standards: Vec<PdfStandard>,
    pub creation_timestamp_millis: Option<f64>,
    pub features: Vec<String>,
    pub should_retain_source_map: bool,
    pub should_tag_pdf: bool,
    pub should_pretty_print: bool,
    pub should_render_bleed: bool,
}

impl Default for CompileOptions {
    fn default() -> Self {
        Self {
            format: "pdf".into(), ppi: 144.0, pages: None, pdf_standards: Vec::new(),
            creation_timestamp_millis: None, features: Vec::new(), should_retain_source_map: true,
            should_tag_pdf: true, should_pretty_print: false, should_render_bleed: false,
        }
    }
}

impl CompileOptions {
    pub fn validate(&self) -> Result<(), String> {
        if !matches!(self.format.as_str(), "pdf" | "svg" | "png" | "html" | "check") {
            return Err(error("Supported output formats: pdf, svg, png, html, check"));
        }
        if !self.ppi.is_finite() || self.ppi <= 0.0 {
            return Err(error("ppi must be a positive finite number"));
        }
        #[cfg(not(typst_v14))]
        if !self.should_tag_pdf { return Err(error("PDF tag control requires Typst 0.14 or newer")); }
        #[cfg(not(typst_v15))]
        if self.should_pretty_print || self.should_render_bleed {
            return Err(error("Pretty output and bleed rendering require Typst 0.15 or newer"));
        }
        Ok(())
    }

    pub fn page_ranges(&self) -> Result<Option<PageRanges>, String> {
        let Some(value) = self.pages.as_deref() else { return Ok(None) };
        let number = |value: &str| -> Result<Option<NonZeroUsize>, String> {
            if value.is_empty() { return Ok(None) }
            value.parse::<usize>().ok().and_then(NonZeroUsize::new).map(Some)
                .ok_or_else(|| error("Page numbers must be positive integers"))
        };
        let mut ranges = Vec::new();
        for item in value.split(',').map(str::trim) {
            if item.is_empty() { return Err(error("Page ranges cannot be empty")) }
            let (start, end) = match item.split_once('-') {
                Some((start, end)) => (number(start.trim())?, number(end.trim())?),
                None => { let page = number(item)?; (page, page) },
            };
            if matches!((start, end), (Some(start), Some(end)) if start > end) {
                return Err(error("Page range start must not exceed its end"));
            }
            ranges.push(start..=end);
        }
        Ok(Some(PageRanges::new(ranges)))
    }
}

pub(crate) fn error(message: &str) -> String { message.to_owned() }
