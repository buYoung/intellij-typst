use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use serde::Serialize;
use time::{OffsetDateTime, UtcOffset};
use typst::diag::{FileError, FileResult};
use typst::foundations::{Bytes, Datetime, Dict, IntoValue};
use typst::syntax::{FileId, Source, VirtualPath};
use typst::text::{Font, FontBook};
use typst::utils::LazyHash;
use typst::{Feature, Library, World};


#[cfg(typst_v14)]
use typst::LibraryExt;

pub(crate) fn file_id(package: Option<&str>, path: &str) -> Result<FileId, String> {
    if path.is_empty() || path.contains(['\\', '\0', ':']) || path.split('/').any(|part| part == "..") {
        return Err(crate::options::error("Use a virtual path without backslashes, ':' or '..' segments"));
    }
    let package = package.map(str::parse).transpose()
        .map_err(|error| crate::options::error(&format!("Invalid package specification: {error}")))?;
    #[cfg(not(typst_v15))]
    { Ok(FileId::new(package, VirtualPath::new(path))) }
    #[cfg(typst_v15)]
    {
        use typst::syntax::{RootedPath, VirtualRoot};
        let root = package.map_or(VirtualRoot::Project, VirtualRoot::Package);
        let path = VirtualPath::new(path).map_err(|error| crate::options::error(&error.to_string()))?;
        Ok(FileId::new(RootedPath::new(root, path)))
    }
}

pub(crate) fn virtual_path(id: FileId) -> String {
    #[cfg(not(typst_v15))]
    { id.vpath().as_rooted_path().to_string_lossy().into_owned() }
    #[cfg(typst_v15)]
    { id.vpath().get_with_slash().to_owned() }
}

pub(crate) fn display_path(id: FileId) -> String {
    package_specification(id).map_or_else(|| virtual_path(id), |package| format!("{package}{}", virtual_path(id)))
}

pub(crate) fn package_specification(id: FileId) -> Option<String> {
    #[cfg(not(typst_v15))]
    let package = id.package();
    #[cfg(typst_v15)]
    let package = match id.root() {
        typst::syntax::VirtualRoot::Package(package) => Some(package),
        typst::syntax::VirtualRoot::Project => None,
    };
    package.map(ToString::to_string)
}

#[derive(Clone, Serialize)]
pub(crate) struct MissingFile {
    pub path: String,
    pub package: Option<String>,
}

#[derive(Clone)]
pub(crate) struct MemoryWorld {
    pub main: FileId,
    library: LazyHash<Library>,
    book: LazyHash<FontBook>,
    fonts: Vec<Option<Font>>,
    #[cfg(feature = "raw")]
    host_fonts: HashMap<usize, (u32, u32)>,
    #[cfg(feature = "raw")]
    loaded_fonts: Arc<Mutex<HashMap<usize, Font>>>,
    files: HashMap<FileId, Bytes>,
    missing: Arc<Mutex<HashSet<FileId>>>,
    inputs: BTreeMap<String, String>,
    features: Vec<Feature>,
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
            fonts: fonts.into_iter().map(Some).collect(),
            #[cfg(feature = "raw")]
            host_fonts: HashMap::new(),
            #[cfg(feature = "raw")]
            loaded_fonts: Arc::new(Mutex::new(HashMap::new())),
            files: HashMap::new(),
            missing: Arc::new(Mutex::new(HashSet::new())),
            inputs: BTreeMap::new(),
            features: Vec::new(),
            timestamp_seconds: 0,
            utc_offset_seconds: 0,
        }
    }

    pub fn set_file(&mut self, id: FileId, data: &[u8]) {
        self.files.insert(id, Bytes::new(data.to_vec()));
    }

    pub fn remove_file(&mut self, id: FileId) { self.files.remove(&id); }

    pub fn reset_files(&mut self) { self.files.clear(); }

    pub fn begin_compile(&mut self) {
        self.missing = Arc::new(Mutex::new(HashSet::new()));
    }

    pub fn missing_files(&self) -> Vec<MissingFile> {
        let mut files: Vec<_> = self.missing.lock().unwrap().iter().map(|id| MissingFile {
            path: virtual_path(*id), package: package_specification(*id),
        }).collect();
        files.sort_by(|a, b| (&a.package, &a.path).cmp(&(&b.package, &b.path)));
        files
    }

    pub fn reset_fonts(&mut self) {
        let fonts: Vec<_> = typst_assets::fonts().flat_map(|data| Font::iter(Bytes::new(data))).collect();
        self.book = LazyHash::new(FontBook::from_fonts(&fonts));
        self.fonts = fonts.into_iter().map(Some).collect();
        #[cfg(feature = "raw")]
        { self.host_fonts.clear(); self.loaded_fonts = Arc::new(Mutex::new(HashMap::new())); }
    }

    pub fn add_font(&mut self, data: &[u8]) -> Result<(), String> {
        let fonts: Vec<_> = Font::iter(Bytes::new(data.to_vec())).collect();
        if fonts.is_empty() { return Err(crate::options::error("No font faces found")); }
        let mut book = (*self.book).clone();
        for font in fonts { book.push(font.info().clone()); self.fonts.push(Some(font)); }
        self.book = LazyHash::new(book);
        Ok(())
    }

    #[cfg(feature = "raw")]
    pub fn register_host_font(&mut self, id: u32, data: &[u8]) -> Result<(), String> {
        let mut book = (*self.book).clone();
        let mut count = 0;
        for font in Font::iter(Bytes::new(data.to_vec())) {
            self.host_fonts.insert(self.fonts.len(), (id, font.index()));
            self.fonts.push(None);
            book.push(font.info().clone());
            count += 1;
        }
        self.book = LazyHash::new(book);
        if count == 0 { return Err("No font faces found".into()) }
        Ok(())
    }

    pub fn set_inputs(&mut self, inputs: BTreeMap<String, String>) {
        self.inputs = inputs;
        self.rebuild_library();
    }

    pub fn set_features(&mut self, names: &[String]) -> Result<(), String> {
        let features: Result<Vec<_>, _> = names.iter().map(|name| match name.as_str() {
            "html" => Ok(Feature::Html),
            #[cfg(typst_v14)]
            "a11y-extras" => Ok(Feature::A11yExtras),
            #[cfg(typst_v15)]
            "bundle" => Ok(Feature::Bundle),
            _ => Err(crate::options::error(&format!("Unsupported Typst feature: {name}"))),
        }).collect();
        self.features = features?;
        self.rebuild_library();
        Ok(())
    }

    fn rebuild_library(&mut self) {
        let inputs: Dict = self.inputs.iter().map(|(key, value)| (key.clone().into(), value.clone().into_value())).collect();
        self.library = LazyHash::new(Library::builder().with_inputs(inputs)
            .with_features(self.features.iter().copied().collect()).build());
    }

    pub fn set_clock(&mut self, timestamp_millis: f64, utc_offset_minutes: i32) -> Result<(), String> {
        if !timestamp_millis.is_finite() || timestamp_millis.abs() > 8_640_000_000_000_000.0 {
            return Err(crate::options::error("Invalid timestampMillis"));
        }
        let seconds = utc_offset_minutes.checked_mul(60)
            .filter(|value| UtcOffset::from_whole_seconds(*value).is_ok())
            .ok_or_else(|| crate::options::error("Invalid utcOffsetMinutes"))?;
        let timestamp_seconds = (timestamp_millis / 1000.0).floor() as i64;
        OffsetDateTime::from_unix_timestamp(timestamp_seconds)
            .map_err(|error| crate::options::error(&error.to_string()))?;
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
        self.files.get(&id).cloned().ok_or_else(|| {
            self.missing.lock().unwrap().insert(id);
            FileError::NotFound(PathBuf::from(display_path(id)))
        })
    }
    fn font(&self, index: usize) -> Option<Font> {
        if let Some(font) = self.fonts.get(index)?.clone() { return Some(font) }
        #[cfg(feature = "raw")]
        {
            let mut loaded = self.loaded_fonts.lock().unwrap();
            if let Some(font) = loaded.get(&index) { return Some(font.clone()) }
            let &(id, face) = self.host_fonts.get(&index)?;
            let font = Font::new(Bytes::new(crate::raw::read_font(id)?), face)?;
            loaded.insert(index, font.clone());
            return Some(font);
        }
        #[cfg(not(feature = "raw"))]
        None
    }
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

impl typst_ide::IdeWorld for MemoryWorld {
    fn upcast(&self) -> &dyn World { self }
}
