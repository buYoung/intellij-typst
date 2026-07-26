use std::fs;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::{Arc, RwLock};
use std::thread;

#[derive(Clone)]
pub struct PreviewServer {
    url: String,
    svg: Arc<RwLock<String>>,
}

impl PreviewServer {
    pub fn start(workspace_id: &str) -> std::io::Result<Self> {
        let listener = TcpListener::bind(("127.0.0.1", 0))?;
        let address = listener.local_addr()?;
        let token = token(workspace_id);
        let svg = Arc::new(RwLock::new(String::new()));
        let server_svg = Arc::clone(&svg);
        let server_token = token.clone();
        thread::Builder::new()
            .name(format!("typst-preview-{workspace_id}"))
            .spawn(move || {
                for stream in listener.incoming().flatten() {
                    serve(stream, &server_token, &server_svg);
                }
            })?;
        Ok(Self {
            url: format!("http://127.0.0.1:{}/{token}/", address.port()),
            svg,
        })
    }

    pub fn url(&self) -> &str {
        &self.url
    }

    pub fn update_from_files(&self, files: &[PathBuf]) -> std::io::Result<()> {
        let mut pages = String::new();
        for (index, file) in files.iter().enumerate() {
            let svg = fs::read_to_string(file)?;
            pages.push_str(&format!(
                "<section class=\"page\" data-page=\"{}\">{}</section>",
                index + 1,
                svg
            ));
        }
        *self.svg.write().expect("preview SVG lock poisoned") = pages;
        Ok(())
    }
}

fn serve(mut stream: TcpStream, token: &str, svg: &Arc<RwLock<String>>) {
    let mut request = [0_u8; 4096];
    let count = stream.read(&mut request).unwrap_or(0);
    let first_line = String::from_utf8_lossy(&request[..count])
        .lines()
        .next()
        .unwrap_or_default()
        .to_owned();
    let expected = format!("GET /{token}/");
    if !first_line.starts_with(&expected) {
        write_response(
            &mut stream,
            "404 Not Found",
            "text/plain",
            "Not found",
            None,
        );
        return;
    }
    let body = page(&svg.read().expect("preview SVG lock poisoned"), token);
    write_response(
        &mut stream,
        "200 OK",
        "text/html; charset=utf-8",
        &body,
        Some(token),
    );
}

fn write_response(
    stream: &mut TcpStream,
    status: &str,
    content_type: &str,
    body: &str,
    nonce: Option<&str>,
) {
    let script_policy = nonce
        .map(|value| format!("'nonce-{value}'"))
        .unwrap_or_else(|| "'none'".to_owned());
    let header = format!(
        "HTTP/1.1 {status}\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nCache-Control: no-store\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src {script_policy}; img-src data: blob:; form-action 'none'; base-uri 'none'\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n",
        body.len()
    );
    let _ = stream.write_all(header.as_bytes());
    let _ = stream.write_all(body.as_bytes());
}

fn page(svg: &str, nonce: &str) -> String {
    format!(
        r#"<!doctype html><meta charset="utf-8"><style>
:root {{ color-scheme: light dark }} html,body {{ max-width:100%;overflow-x:hidden }}
body {{ margin:0;background:#777 }}
#pages {{ --preview-scale:1;display:grid;gap:16px;padding:16px;box-sizing:border-box;width:100%;justify-items:center }}
.page {{ width:calc(100% * var(--preview-scale));max-width:100%;background:white;box-shadow:0 2px 12px #0006 }}
.page svg {{ display:block;width:100%;max-width:100%;height:auto }}
body.invert .page {{ filter:invert(1) hue-rotate(180deg) }}
</style><div id="pages">{svg}</div><script nonce="{nonce}">
let scale=1; const pages=document.getElementById('pages');
window.typstPreview={{
 setScale:v=>{{scale=Math.max(.2,Math.min(1,Number(v)||1));pages.style.setProperty('--preview-scale',scale);save()}},
 setInvert:v=>{{document.body.classList.toggle('invert',!!v);save()}},
 state:()=>({{scale,scrollY:window.scrollY}}),
 restore:s=>{{if(s){{window.typstPreview.setScale(s.scale||1);scrollTo(0,s.scrollY||0)}}}}
}};
const save=()=>sessionStorage.setItem('typst-preview-state',JSON.stringify(window.typstPreview.state()));
addEventListener('scroll',save,{{passive:true}});
try{{window.typstPreview.restore(JSON.parse(sessionStorage.getItem('typst-preview-state')))}}catch(_e){{}}
</script>"#
    )
}

fn token(workspace_id: &str) -> String {
    use sha2::{Digest, Sha256};
    let mut hasher = Sha256::new();
    hasher.update(workspace_id.as_bytes());
    hasher.update(std::process::id().to_le_bytes());
    hasher.update(
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos()
            .to_le_bytes(),
    );
    format!("{:x}", hasher.finalize())[..32].to_owned()
}

pub fn find_svg_pages(pattern: &Path) -> std::io::Result<Vec<PathBuf>> {
    let file_name = pattern
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or_default();
    if !file_name.contains("{p}") {
        return Ok(if pattern.exists() {
            vec![pattern.to_owned()]
        } else {
            Vec::new()
        });
    }
    let (prefix, suffix) = file_name.split_once("{p}").unwrap_or_default();
    let mut pages = fs::read_dir(pattern.parent().unwrap_or_else(|| Path::new(".")))?
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| {
            path.file_name()
                .and_then(|name| name.to_str())
                .is_some_and(|name| name.starts_with(prefix) && name.ends_with(suffix))
        })
        .collect::<Vec<_>>();
    pages.sort();
    Ok(pages)
}
