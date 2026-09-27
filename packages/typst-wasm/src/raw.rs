//! Host-neutral ABI. The caller owns alloc/dealloc buffers and serializes requests.
//! A trapped or interrupted instance must be discarded, never reused.
use std::cell::RefCell;
use serde::Deserialize;
use serde_json::{json, Value};
use crate::{Compiler, CompileOptions, file_id};

thread_local! {
    static COMPILER: RefCell<Compiler> = RefCell::new(Compiler::new());
    static OUTPUTS: RefCell<Vec<Vec<u8>>> = const { RefCell::new(Vec::new()) };
}

#[link(wasm_import_module = "typst_host")]
extern "C" { fn read_font_bytes(id: u32, pointer: *mut u8, length: usize) -> i32; }

pub(crate) fn read_font(id: u32) -> Option<Vec<u8>> {
    // A zero-length call asks for the size; the second copies into guest-owned memory.
    let length = unsafe { read_font_bytes(id, std::ptr::null_mut(), 0) };
    if length <= 0 { return None }
    let mut bytes = vec![0; length as usize];
    (unsafe { read_font_bytes(id, bytes.as_mut_ptr(), bytes.len()) } == length).then_some(bytes)
}

#[derive(Deserialize)]
#[serde(tag = "method", rename_all = "camelCase")]
enum Request {
    Version,
    SetFile { path: String, package: Option<String> },
    RemoveFile { path: String },
    ResetFiles,
    AddFont,
    RegisterFont { id: u32 },
    ResetFonts,
    SetInputs { inputs: std::collections::BTreeMap<String, String> },
    Compile {
        path: String,
        #[serde(rename = "timestampMillis")] timestamp_millis: f64,
        #[serde(rename = "utcOffsetMinutes")] utc_offset_minutes: i32,
        options: CompileOptions,
    },
    DocumentToSource { page: usize, x: f64, y: f64 },
    SourceToDocument { path: String, #[serde(rename = "utf16Offset")] utf16_offset: usize },
}

fn dispatch(request: Request, bytes: &[u8]) -> Result<Value, String> {
    COMPILER.with(|cell| {
        let mut compiler = cell.borrow_mut();
        match request {
            Request::Version => return Ok(json!({ "apiVersion": 1, "typstVersion": crate::version() })),
            Request::SetFile { path, package } => compiler.world.set_file(file_id(package.as_deref(), &path)?, bytes),
            Request::RemoveFile { path } => compiler.world.remove_file(file_id(None, &path)?),
            Request::ResetFiles => compiler.world.reset_files(),
            Request::AddFont => compiler.world.add_font(bytes)?,
            Request::RegisterFont { id } => compiler.world.register_host_font(id, bytes)?,
            Request::ResetFonts => compiler.world.reset_fonts(),
            Request::SetInputs { inputs } => compiler.world.set_inputs(inputs),
            Request::Compile { path, timestamp_millis, utc_offset_minutes, options } => {
                OUTPUTS.with(|outputs| outputs.borrow_mut().clear());
                let mut output = compiler.compile_document(&path, timestamp_millis, utc_offset_minutes, options)?;
                let mut binary = Vec::new();
                if !output.pdf.is_empty() { binary.push(std::mem::take(&mut output.pdf)); }
                binary.extend(std::mem::take(&mut output.png_pages).into_iter().map(|bytes| bytes.into_vec()));
                let count = binary.len();
                OUTPUTS.with(|outputs| *outputs.borrow_mut() = binary);
                let mut value = serde_json::to_value(output).map_err(|e| e.to_string())?;
                value["binaryOutputCount"] = json!(count);
                return Ok(value);
            }
            Request::DocumentToSource { page, x, y } => return serde_json::to_value(compiler.document.as_ref()
                .and_then(|document| document.document_to_source(page, x, y))).map_err(|e| e.to_string()),
            Request::SourceToDocument { path, utf16_offset } => return serde_json::to_value(compiler.document.as_ref()
                .map(|document| document.source_to_document(&path, utf16_offset)).unwrap_or_default()).map_err(|e| e.to_string()),
        }
        Ok(Value::Null)
    })
}

fn transfer(bytes: Vec<u8>) -> u64 {
    if bytes.is_empty() { return 0 }
    let bytes = bytes.into_boxed_slice();
    let length = bytes.len() as u64;
    let pointer = Box::into_raw(bytes) as *mut u8 as u64;
    (pointer << 32) | length
}

#[no_mangle]
pub extern "C" fn alloc(length: usize) -> *mut u8 {
    if length == 0 { return std::ptr::null_mut() }
    Box::into_raw(vec![0_u8; length].into_boxed_slice()) as *mut u8
}

#[no_mangle]
pub unsafe extern "C" fn dealloc(pointer: *mut u8, length: usize) {
    if !pointer.is_null() && length > 0 { drop(Box::from_raw(std::ptr::slice_from_raw_parts_mut(pointer, length))); }
}

#[no_mangle]
pub unsafe extern "C" fn request(pointer: *const u8, length: usize, data_pointer: *const u8, data_length: usize) -> u64 {
    let bytes = if data_length == 0 { &[] } else { std::slice::from_raw_parts(data_pointer, data_length) };
    let result = serde_json::from_slice(std::slice::from_raw_parts(pointer, length)).map_err(|e| e.to_string())
        .and_then(|request| dispatch(request, bytes));
    let response = match result { Ok(value) => json!({ "ok": value }), Err(error) => json!({ "error": error }) };
    transfer(serde_json::to_vec(&response).expect("JSON response"))
}

#[no_mangle]
pub extern "C" fn take_output(index: usize) -> u64 {
    OUTPUTS.with(|outputs| outputs.borrow_mut().get_mut(index).map(|bytes| transfer(std::mem::take(bytes))).unwrap_or(0))
}
