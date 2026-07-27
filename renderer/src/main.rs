mod package;
mod preview;
mod protocol;
mod workspace;

use protocol::{Request, RequestMessage, Response, PROTOCOL_VERSION};
use serde_json::json;
use std::collections::HashMap;
use std::io::{self, BufRead, Write};
use workspace::Workspace;

fn main() {
    // Keep all dependencies linked and make the exact renderer version observable without
    // exposing any Tinymist executable, LSP, or protocol surface.
    let _typst_features = typst::Features::default();

    let stdin = io::stdin();
    let mut stdout = io::BufWriter::new(io::stdout().lock());
    let mut workspaces: HashMap<String, Workspace> = HashMap::new();
    for line in stdin.lock().lines() {
        let line = match line {
            Ok(line) if !line.trim().is_empty() => line,
            Ok(_) => continue,
            Err(_) => break,
        };
        let request: Request = match serde_json::from_str(&line) {
            Ok(request) => request,
            Err(error) => {
                let response = json!({
                    "id": "",
                    "workspaceId": "",
                    "documentVersion": 0,
                    "generation": 0,
                    "protocolVersion": PROTOCOL_VERSION,
                    "status": "error",
                    "error": {"code": "invalidRequest", "message": error.to_string()}
                });
                let _ = writeln!(stdout, "{response}");
                let _ = stdout.flush();
                continue;
            }
        };
        let should_shutdown = matches!(&request.message, RequestMessage::Shutdown);
        let response = handle(&mut workspaces, &request);
        if writeln!(
            stdout,
            "{}",
            serde_json::to_string(&response).expect("response serialization failed")
        )
        .is_err()
        {
            break;
        }
        let _ = stdout.flush();
        if should_shutdown {
            break;
        }
    }
}

fn handle(workspaces: &mut HashMap<String, Workspace>, request: &Request) -> Response {
    let result = match &request.message {
        RequestMessage::Initialize(params) => Workspace::new(&request.workspace_id, params.clone())
            .map(|workspace| {
                workspaces.insert(request.workspace_id.clone(), workspace);
                json!({
                    "runtimeVersion": env!("CARGO_PKG_VERSION"),
                    "typstVersion": "0.15.0",
                    "reflexoVersion": "0.8.0-rc3",
                })
            }),
        RequestMessage::UpdateSource(params) => workspace_mut(workspaces, request)
            .and_then(|workspace| workspace.update_source(&params.uri, params.text.clone()))
            .map(|_| json!({})),
        RequestMessage::Compile(params) => workspace_mut(workspaces, request)
            .and_then(|workspace| workspace.compile(request.generation, params.render))
            .and_then(|result| serde_json::to_value(result).map_err(|error| error.to_string())),
        RequestMessage::SourceToDocument(position) => {
            let _ = (&position.uri, position.line, position.column);
            Ok(json!({"mapped": false}))
        }
        RequestMessage::DocumentToSource(position) => workspace_mut(workspaces, request)
            .and_then(|workspace| {
                workspace.document_to_source(
                    request.generation,
                    position.page,
                    position.x,
                    position.y,
                )
            })
            .and_then(|result| serde_json::to_value(result).map_err(|error| error.to_string())),
        RequestMessage::PackageIndex(params) => {
            package::fetch_text(&params.index_url, params.max_bytes)
                .map(|text| json!({"index": text}))
        }
        RequestMessage::EnsurePackage(params) => {
            workspace_mut(workspaces, request).and_then(|workspace| {
                package::ensure_package(
                    &params.specification,
                    &params.archive_url,
                    params.sha256.as_deref(),
                    params.max_bytes,
                    &workspace.package_cache_root(),
                )
                .map(|path| json!({"path": path}))
            })
        }
        RequestMessage::Shutdown => Ok(json!({})),
    };
    match result {
        Ok(value) => Response::success(request, value),
        Err(message) => Response::error(request, "operationFailed", message),
    }
}

fn workspace_mut<'a>(
    workspaces: &'a mut HashMap<String, Workspace>,
    request: &Request,
) -> Result<&'a mut Workspace, String> {
    workspaces
        .get_mut(&request.workspace_id)
        .ok_or_else(|| "workspace is not initialized".to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;
    use protocol::{CompileParams, RequestMessage};

    #[test]
    fn response_preserves_correlation_fields() {
        let request = Request {
            id: "request-1".to_owned(),
            workspace_id: "workspace-1".to_owned(),
            document_version: 7,
            generation: 9,
            message: RequestMessage::Compile(CompileParams { render: false }),
        };
        let response = Response::error(&request, "test", "failure");
        assert_eq!(response.id, "request-1");
        assert_eq!(response.workspace_id, "workspace-1");
        assert_eq!(response.document_version, 7);
        assert_eq!(response.generation, 9);
        assert_eq!(response.protocol_version, 1);
    }
}
