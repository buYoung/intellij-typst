use std::collections::{BTreeMap, HashMap};
use std::path::PathBuf;
use time::{OffsetDateTime, UtcOffset};
use typst::diag::{FileError, FileResult};
use typst::foundations::{Bytes, Datetime, Dict, IntoValue};
use typst::syntax::{FileId, Source, VirtualPath};
use typst::text::{Font, FontBook};
use typst::utils::LazyHash;
use typst::{Library, World};
use wasm_bindgen::prelude::*;

#[cfg(typst_v14)]
use typst::LibraryExt;

pub(crate) fn file_id(package: Option<&str>, path: &str) -> Result<FileId, JsValue> {
    if path.is_empty() || path.contains(['\\', '\0', ':']) || path.split('/').any(|part| part == "..") {
        return Err(JsValue::from_str("Use a virtual path without backslashes, ':' or '..' segments"));
    }
    let package = package.map(str::parse).transpose()
        .map_err(|error| JsValue::from_str(&format!("Invalid package specification: {error}")))?;
    #[cfg(not(typst_v15))]
    { Ok(FileId::new(package, VirtualPath::new(path))) }
    #[cfg(typst_v15)]
    {
        use typst::syntax::{RootedPath, VirtualRoot};
        let root = package.map_or(VirtualRoot::Project, VirtualRoot::Package);
        let path = VirtualPath::new(path).map_err(|error| JsValue::from_str(&error.to_string()))?;
        Ok(FileId::new(RootedPath::new(root, path)))
    }
}

fn virtual_path(id: FileId) -> String {
    #[cfg(not(typst_v15))]
    { id.vpath().as_rooted_path().to_string_lossy().into_owned() }
    #[cfg(typst_v15)]
    { id.vpath().get_with_slash().to_owned() }
}

pub(crate) fn display_path(id: FileId) -> String {
    #[cfg(not(typst_v15))]
    let package = id.package();
    #[cfg(typst_v15)]
    let package = match id.root() {
        typst::syntax::VirtualRoot::Package(package) => Some(package),
        typst::syntax::VirtualRoot::Project => None,
    };
    package.map_or_else(|| virtual_path(id), |package| format!("{package}{}", virtual_path(id)))
}

pub(crate) struct MemoryWorld {
    pub main: FileId,
    library: LazyHash<Library>,
    book: LazyHash<FontBook>,
    fonts: Vec<Font>,
    files: HashMap<FileId, Bytes>,
    timestamp_seconds: i64,
    utc_offset_seconds: i32,
}

impl MemoryWorld {
    pub fn new() -> Self {
        let fonts: Vec<_> = typst_assets::fonts()
            .flat_map(|data| Font::iter(Bytes::new(data))).collect();
        Self {
            main: file_id(None, "/main.typ").expect("valid fixed entrypoint"),
            library: LazyHash::new(Library::default()),
            book: LazyHash::new(FontBook::from_fonts(&fonts)),
            fonts,
            files: HashMap::new(),
            timestamp_seconds: 0,
            utc_offset_seconds: 0,
        }
    }

    pub fn set_file(&mut self, id: FileId, data: &[u8]) {
        self.files.insert(id, Bytes::new(data.to_vec()));
    }

    pub fn remove_file(&mut self, id: FileId) { self.files.remove(&id); }

    pub fn reset_files(&mut self) { self.files.clear(); }

    pub fn add_font(&mut self, data: &[u8]) -> Result<(), JsValue> {
        let fonts: Vec<_> = Font::iter(Bytes::new(data.to_vec())).collect();
        if fonts.is_empty() { return Err(JsValue::from_str("No font faces found")); }
        self.fonts.extend(fonts);
        self.book = LazyHash::new(FontBook::from_fonts(&self.fonts));
        Ok(())
    }

    pub fn set_inputs(&mut self, inputs: BTreeMap<String, String>) {
        let inputs: Dict = inputs.into_iter().map(|(key, value)| (key.into(), value.into_value())).collect();
        self.library = LazyHash::new(Library::builder().with_inputs(inputs).build());
    }

    pub fn set_clock(&mut self, timestamp_millis: f64, utc_offset_minutes: i32) -> Result<(), JsValue> {
        if !timestamp_millis.is_finite() || timestamp_millis.abs() > 8_640_000_000_000_000.0 {
            return Err(JsValue::from_str("Invalid timestampMillis"));
        }
        let seconds = utc_offset_minutes.checked_mul(60)
            .filter(|value| UtcOffset::from_whole_seconds(*value).is_ok())
            .ok_or_else(|| JsValue::from_str("Invalid utcOffsetMinutes"))?;
        let timestamp_seconds = (timestamp_millis / 1000.0).floor() as i64;
        OffsetDateTime::from_unix_timestamp(timestamp_seconds)
            .map_err(|error| JsValue::from_str(&error.to_string()))?;
        self.timestamp_seconds = timestamp_seconds;
        self.utc_offset_seconds = seconds;
        Ok(())
    }

    fn date(&self, offset_seconds: Option<i32>) -> Option<Datetime> {
        let offset = UtcOffset::from_whole_seconds(offset_seconds.unwrap_or(self.utc_offset_seconds)).ok()?;
        let date = OffsetDateTime::from_unix_timestamp(self.timestamp_seconds).ok()?.to_offset(offset);
        Datetime::from_ymd(date.year(), date.month() as u8, date.day())
    }
}

impl World for MemoryWorld {
    fn library(&self) -> &LazyHash<Library> { &self.library }
    fn book(&self) -> &LazyHash<FontBook> { &self.book }
    fn main(&self) -> FileId { self.main }
    fn source(&self, id: FileId) -> FileResult<Source> {
        let bytes = self.file(id)?;
        let text = std::str::from_utf8(&bytes).map_err(|_| FileError::InvalidUtf8)?;
        Ok(Source::new(id, text.to_owned()))
    }
    fn file(&self, id: FileId) -> FileResult<Bytes> {
        self.files.get(&id).cloned().ok_or_else(|| FileError::NotFound(PathBuf::from(display_path(id))))
    }
    fn font(&self, index: usize) -> Option<Font> { self.fonts.get(index).cloned() }
    #[cfg(not(typst_v15))]
    fn today(&self, offset: Option<i64>) -> Option<Datetime> {
        let seconds = match offset {
            Some(hours) => Some(hours.checked_mul(3600)?.try_into().ok()?),
            None => None,
        };
        self.date(seconds)
    }
    #[cfg(typst_v15)]
    fn today(&self, offset: Option<typst::foundations::Duration>) -> Option<Datetime> {
        self.date(offset.map(|duration| duration.seconds() as i32))
    }
}
