use serde::Serialize;
use typst::layout::{Abs, Point};
use typst::syntax::{LinkedNode, Side};
use typst::World;
use typst_ide::{jump_from_click, jump_from_cursor, Jump};
use crate::{PagedDocument, world::{MemoryWorld, file_id, display_path}};

pub(crate) struct DocumentSnapshot {
    pub world: MemoryWorld,
    pub document: PagedDocument,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct SourcePosition {
    pub path: String,
    pub byte_start: usize,
    pub byte_end: usize,
    pub utf16_start: usize,
    pub utf16_end: usize,
    pub line: usize,
    pub column: usize,
    pub end_line: usize,
    pub end_column: usize,
}

#[derive(Serialize)]
pub(crate) struct DocumentPosition {
    pub page: usize,
    pub x: f64,
    pub y: f64,
}

pub(crate) fn line_column(text: &str, offset: usize) -> Option<(usize, usize)> {
    let prefix = text.get(..offset)?;
    let line = prefix.bytes().filter(|byte| *byte == b'\n').count();
    let column = prefix.rsplit('\n').next()?.encode_utf16().count();
    Some((line, column))
}

impl DocumentSnapshot {
    pub fn document_to_source(&self, page: usize, x: f64, y: f64) -> Option<SourcePosition> {
        if !x.is_finite() || !y.is_finite() || page == 0 { return None }
        let point = Point::new(Abs::pt(x), Abs::pt(y));
        #[cfg(typst_v15)]
        let jump = {
            let position = typst::introspection::PagedPosition { page: std::num::NonZeroUsize::new(page)?, point };
            jump_from_click(&self.world, &self.document, &position)
        };
        #[cfg(not(typst_v15))]
        let jump = jump_from_click(&self.world, &self.document, &self.document.pages.get(page - 1)?.frame, point);
        let Jump::File(id, offset) = jump? else { return None };
        let source = self.world.source(id).ok()?;
        let node = LinkedNode::new(source.root());
        let range = node.leaf_at(offset, Side::After).or_else(|| node.leaf_at(offset, Side::Before))
            .map(|node| node.range()).unwrap_or(offset..offset);
        let text = source.text();
        let (line, column) = line_column(text, range.start)?;
        let (end_line, end_column) = line_column(text, range.end)?;
        Some(SourcePosition {
            path: display_path(id), byte_start: range.start, byte_end: range.end,
            utf16_start: text.get(..range.start)?.encode_utf16().count(),
            utf16_end: text.get(..range.end)?.encode_utf16().count(),
            line, column, end_line, end_column,
        })
    }

    pub fn source_to_document(&self, path: &str, utf16_offset: usize) -> Vec<DocumentPosition> {
        let Some(source) = file_id(None, path).ok().and_then(|id| self.world.source(id).ok()) else { return Vec::new() };
        let mut units = 0;
        let mut byte_offset = None;
        for (offset, character) in source.text().char_indices() {
            if units == utf16_offset { byte_offset = Some(offset); break }
            units += character.len_utf16();
        }
        if units == utf16_offset && byte_offset.is_none() { byte_offset = Some(source.text().len()) }
        let Some(offset) = byte_offset else { return Vec::new() };
        jump_from_cursor(&self.document, &source, offset).into_iter().map(|position| DocumentPosition {
            page: position.page.get(), x: position.point.x.to_pt(), y: position.point.y.to_pt(),
        }).collect()
    }
}
