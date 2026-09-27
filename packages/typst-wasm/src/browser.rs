use wasm_bindgen::prelude::*;
use crate::{CompileOptions, file_id};

#[wasm_bindgen]
pub fn version() -> String { crate::version() }

#[wasm_bindgen]
pub struct Compiler(crate::Compiler);

fn js_error(message: String) -> JsValue { JsValue::from_str(&message) }

#[wasm_bindgen]
impl Compiler {
    #[wasm_bindgen(constructor)]
    pub fn new() -> Self {
        console_error_panic_hook::set_once();
        Self(crate::Compiler::new())
    }
    pub fn set_source(&mut self, path: &str, text: &str) -> Result<(), JsValue> { self.set_file(path, text.as_bytes()) }
    pub fn set_file(&mut self, path: &str, data: &[u8]) -> Result<(), JsValue> {
        self.0.world.set_file(file_id(None, path).map_err(js_error)?, data);
        Ok(())
    }
    pub fn set_package_file(&mut self, specification: &str, path: &str, data: &[u8]) -> Result<(), JsValue> {
        self.0.world.set_file(file_id(Some(specification), path).map_err(js_error)?, data);
        Ok(())
    }
    pub fn remove_file(&mut self, path: &str) -> Result<(), JsValue> {
        self.0.world.remove_file(file_id(None, path).map_err(js_error)?);
        Ok(())
    }
    pub fn reset_files(&mut self) { self.0.world.reset_files(); }
    pub fn add_font(&mut self, data: &[u8]) -> Result<(), JsValue> { self.0.world.add_font(data).map_err(js_error) }
    pub fn reset_fonts(&mut self) { self.0.world.reset_fonts(); }
    pub fn set_inputs(&mut self, inputs: JsValue) -> Result<(), JsValue> {
        self.0.world.set_inputs(serde_wasm_bindgen::from_value(inputs).map_err(|e| js_error(e.to_string()))?);
        Ok(())
    }
    pub fn document_to_source(&self, page: usize, x: f64, y: f64) -> Result<JsValue, JsValue> {
        serde_wasm_bindgen::to_value(&self.0.document.as_ref().and_then(|doc| doc.document_to_source(page, x, y)))
            .map_err(|e| js_error(e.to_string()))
    }
    pub fn source_to_document(&self, path: &str, utf16_offset: usize) -> Result<JsValue, JsValue> {
        serde_wasm_bindgen::to_value(&self.0.document.as_ref().map(|doc| doc.source_to_document(path, utf16_offset)).unwrap_or_default())
            .map_err(|e| js_error(e.to_string()))
    }
    pub fn compile(&mut self, main_path: &str, format: &str, timestamp_millis: f64, utc_offset_minutes: i32) -> Result<JsValue, JsValue> {
        let output = self.0.compile_document(main_path, timestamp_millis, utc_offset_minutes, CompileOptions { format: format.into(), ..Default::default() }).map_err(js_error)?;
        serde_wasm_bindgen::to_value(&output).map_err(|e| js_error(e.to_string()))
    }
    pub fn compile_with_options(&mut self, main_path: &str, timestamp_millis: f64, utc_offset_minutes: i32, options: JsValue) -> Result<JsValue, JsValue> {
        let options = serde_wasm_bindgen::from_value(options).map_err(|e| js_error(e.to_string()))?;
        let output = self.0.compile_document(main_path, timestamp_millis, utc_offset_minutes, options).map_err(js_error)?;
        serde_wasm_bindgen::to_value(&output).map_err(|e| js_error(e.to_string()))
    }
}
