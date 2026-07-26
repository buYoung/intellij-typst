use serde::{Deserialize, Serialize};
use serde_json::Value;

pub const PROTOCOL_VERSION: u32 = 1;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Request {
    pub id: String,
    pub workspace_id: String,
    #[serde(default)]
    pub document_version: i64,
    #[serde(default)]
    pub generation: u64,
    #[serde(flatten)]
    pub message: RequestMessage,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "method", content = "params", rename_all = "camelCase")]
pub enum RequestMessage {
    Initialize(InitializeParams),
    UpdateSource(UpdateSourceParams),
    Compile(CompileParams),
    SourceToDocument(SourcePosition),
    DocumentToSource(DocumentPosition),
    PackageIndex(PackageIndexParams),
    EnsurePackage(EnsurePackageParams),
    Shutdown,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InitializeParams {
    pub root_uri: String,
    pub main_uri: String,
    pub typst_executable: String,
    #[serde(default)]
    pub font_paths: Vec<String>,
    #[serde(default = "default_true")]
    pub use_system_fonts: bool,
    pub package_path: Option<String>,
    pub package_cache_path: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateSourceParams {
    pub uri: String,
    pub text: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CompileParams {
    #[serde(default)]
    pub render: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SourcePosition {
    pub uri: String,
    pub line: u32,
    pub column: u32,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DocumentPosition {
    pub page: u32,
    pub x: f64,
    pub y: f64,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PackageIndexParams {
    pub index_url: String,
    pub max_bytes: u64,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EnsurePackageParams {
    pub specification: String,
    pub archive_url: String,
    pub sha256: Option<String>,
    pub max_bytes: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Response {
    pub id: String,
    pub workspace_id: String,
    pub document_version: i64,
    pub generation: u64,
    pub protocol_version: u32,
    pub status: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<ProtocolError>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProtocolError {
    pub code: &'static str,
    pub message: String,
}

impl Response {
    pub fn success(request: &Request, result: Value) -> Self {
        Self {
            id: request.id.clone(),
            workspace_id: request.workspace_id.clone(),
            document_version: request.document_version,
            generation: request.generation,
            protocol_version: PROTOCOL_VERSION,
            status: "ok",
            result: Some(result),
            error: None,
        }
    }

    pub fn error(request: &Request, code: &'static str, message: impl Into<String>) -> Self {
        Self {
            id: request.id.clone(),
            workspace_id: request.workspace_id.clone(),
            document_version: request.document_version,
            generation: request.generation,
            protocol_version: PROTOCOL_VERSION,
            status: "error",
            result: None,
            error: Some(ProtocolError {
                code,
                message: message.into(),
            }),
        }
    }
}

fn default_true() -> bool {
    true
}
