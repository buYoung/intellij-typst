use serde::Serialize;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::{Arc, RwLock};
use std::thread;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SemanticRegion {
    pub kind: &'static str,
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub page: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub target_x: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub target_y: Option<f64>,
}

#[derive(Clone)]
pub struct PreviewServer {
    url: String,
    state: Arc<RwLock<PreviewState>>,
}

#[derive(Default)]
struct PreviewState {
    generation: u64,
    pages: Vec<PageResource>,
    resources: HashMap<String, PageContent>,
}

struct PageResource {
    number: u32,
    width: f64,
    height: f64,
    key: String,
}

struct PageContent {
    svg: String,
    regions_json: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Snapshot<'a> {
    generation: u64,
    pages: Vec<SnapshotPage<'a>>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SnapshotPage<'a> {
    number: u32,
    width: f64,
    height: f64,
    key: &'a str,
}

impl PreviewServer {
    pub fn start(workspace_id: &str) -> std::io::Result<Self> {
        let listener = TcpListener::bind(("127.0.0.1", 0))?;
        let address = listener.local_addr()?;
        let token = token(workspace_id);
        let state = Arc::new(RwLock::new(PreviewState::default()));
        let server_state = Arc::clone(&state);
        let server_token = token.clone();
        thread::Builder::new()
            .name(format!("typst-preview-{workspace_id}"))
            .spawn(move || {
                for stream in listener.incoming().flatten() {
                    serve(stream, &server_token, &server_state);
                }
            })?;
        Ok(Self {
            url: format!("http://127.0.0.1:{}/{token}/", address.port()),
            state,
        })
    }

    pub fn url(&self) -> &str {
        &self.url
    }

    pub fn update(
        &self,
        generation: u64,
        pages: Vec<(String, f64, f64, Vec<SemanticRegion>)>,
    ) -> Result<(), serde_json::Error> {
        let mut next_pages = Vec::with_capacity(pages.len());
        let mut next_resources = HashMap::with_capacity(pages.len());
        for (index, (svg, width, height, regions)) in pages.into_iter().enumerate() {
            let regions_json = serde_json::to_string(&regions)?;
            let mut hasher = Sha256::new();
            hasher.update(svg.as_bytes());
            hasher.update(regions_json.as_bytes());
            let key = format!("{:x}", hasher.finalize());
            next_pages.push(PageResource {
                number: index as u32 + 1,
                width,
                height,
                key: key.clone(),
            });
            next_resources.insert(key, PageContent { svg, regions_json });
        }
        let mut state = self.state.write().expect("preview state lock poisoned");
        state.generation = generation;
        state.pages = next_pages;
        state.resources = next_resources;
        Ok(())
    }
}

fn serve(mut stream: TcpStream, token: &str, state: &Arc<RwLock<PreviewState>>) {
    let mut request = [0_u8; 4096];
    let count = stream.read(&mut request).unwrap_or(0);
    let request_text = String::from_utf8_lossy(&request[..count]);
    let first_line = request_text.lines().next().unwrap_or_default();
    let Some(request_path) = first_line
        .strip_prefix("GET ")
        .and_then(|line| line.split_once(' ').map(|(path, _)| path))
    else {
        return not_found(&mut stream);
    };
    let path = request_path.split('?').next().unwrap_or(request_path);
    let base = format!("/{token}/");
    if path == base {
        return write_response(
            &mut stream,
            "200 OK",
            "text/html; charset=utf-8",
            &shell(token),
            Some(token),
        );
    }
    if path == format!("{base}snapshot.json") {
        let state = state.read().expect("preview state lock poisoned");
        let body = serde_json::to_string(&Snapshot {
            generation: state.generation,
            pages: state
                .pages
                .iter()
                .map(|page| SnapshotPage {
                    number: page.number,
                    width: page.width,
                    height: page.height,
                    key: &page.key,
                })
                .collect(),
        })
        .unwrap_or_else(|_| "{\"generation\":0,\"pages\":[]}".to_owned());
        return write_response(
            &mut stream,
            "200 OK",
            "application/json; charset=utf-8",
            &body,
            None,
        );
    }
    let Some(resource) = path.strip_prefix(&format!("{base}pages/")) else {
        return not_found(&mut stream);
    };
    let Some((key, extension)) = resource.rsplit_once('.') else {
        return not_found(&mut stream);
    };
    if key.len() != 64 || !key.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return not_found(&mut stream);
    }
    let state = state.read().expect("preview state lock poisoned");
    let Some(content) = state.resources.get(key) else {
        return not_found(&mut stream);
    };
    match extension {
        "svg" => write_response(
            &mut stream,
            "200 OK",
            "image/svg+xml; charset=utf-8",
            &content.svg,
            None,
        ),
        "json" => write_response(
            &mut stream,
            "200 OK",
            "application/json; charset=utf-8",
            &content.regions_json,
            None,
        ),
        _ => not_found(&mut stream),
    }
}

fn not_found(stream: &mut TcpStream) {
    write_response(stream, "404 Not Found", "text/plain", "Not found", None);
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
        "HTTP/1.1 {status}\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nCache-Control: no-store\r\nContent-Security-Policy: default-src 'self'; style-src 'unsafe-inline'; script-src {script_policy}; img-src 'self' data: blob:; connect-src 'self'; form-action 'none'; base-uri 'none'\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n",
        body.len()
    );
    let _ = stream.write_all(header.as_bytes());
    let _ = stream.write_all(body.as_bytes());
}

fn shell(nonce: &str) -> String {
    format!(
        r#"<!doctype html><meta charset="utf-8"><style>
:root {{ color-scheme:light dark }} html,body {{ max-width:100%;overflow-x:hidden }}
body {{ margin:0;background:#777 }}
#pages {{ --preview-scale:1;display:grid;gap:16px;padding:16px;box-sizing:border-box;width:100%;justify-items:center }}
.page {{ position:relative;width:calc(100% * var(--preview-scale));max-width:100%;background:white;box-shadow:0 2px 12px #0006;overflow:hidden }}
.page svg {{ display:block;width:100%;max-width:100%;height:auto }}
.placeholder {{ width:100%;height:100%;background:#fff }}
.semantic {{ position:absolute;box-sizing:border-box;background:transparent }}
.semantic.text {{ cursor:text }} .semantic.link {{ cursor:pointer }} .semantic.shape {{ cursor:grab }}
.semantic.text:hover {{ outline:1.5px solid #f75c2f;background:#f75c2f20 }}
.semantic.link:hover {{ outline:1.5px solid #66bab7;background:#66bab724 }}
.semantic.shape:hover {{ outline:1.5px solid #29a6a6 }}
body.dragging,body.dragging * {{ cursor:grabbing!important;user-select:none!important }}
.selection-line {{ position:absolute;box-sizing:border-box;background:#f75c2f2e;border-left:3px solid #f75c2f;box-shadow:0 0 0 1px #f75c2f4d inset;pointer-events:none }}
body.invert .page {{ filter:invert(1) hue-rotate(180deg) }}
</style><div id="pages"></div><script nonce="{nonce}">
(() => {{
 let scale=1,generation=-1,drag=null,pendingSelection=null,shownSelectionToken=null; const pages=document.getElementById('pages');
 const save=()=>sessionStorage.setItem('typst-preview-state',JSON.stringify(window.typstPreview.state()));
 const visible=new IntersectionObserver(entries=>entries.forEach(e=>{{if(e.isIntersecting)loadPage(e.target)}}),{{rootMargin:'1200px 0px'}});
 async function loadPage(section){{
   const key=section.dataset.key;if(!key||section.dataset.loaded===key)return;
   const [svg,regions]=await Promise.all([fetch('pages/'+key+'.svg').then(r=>r.text()),fetch('pages/'+key+'.json').then(r=>r.json())]);
   if(section.dataset.key!==key)return;section.innerHTML=svg;section.dataset.loaded=key;
   const pageWidth=Number(section.dataset.width),pageHeight=Number(section.dataset.height);
   for(const region of regions){{
     const node=document.createElement(region.kind==='link'&&region.url?'a':'div');node.className='semantic '+region.kind;
     Object.assign(node.style,{{left:(region.x/pageWidth*100)+'%',top:(region.y/pageHeight*100)+'%',width:(region.width/pageWidth*100)+'%',height:(region.height/pageHeight*100)+'%'}});
     node.dataset.x=region.x;node.dataset.y=region.y;node.dataset.width=region.width;node.dataset.height=region.height;
     if(region.url)node.href=region.url;
     if(region.page){{node.dataset.targetPage=region.page;node.dataset.targetX=region.targetX||0;node.dataset.targetY=region.targetY||0}}
     section.appendChild(node);
   }}
 }}
 async function refresh(){{
   try{{const snapshot=await fetch('snapshot.json?'+Date.now()).then(r=>r.json());if(snapshot.generation===generation)return;generation=snapshot.generation;
     const old=new Map([...pages.children].map(e=>[e.dataset.page+':'+e.dataset.key,e]));const fragment=document.createDocumentFragment();
     for(const page of snapshot.pages){{let section=old.get(page.number+':'+page.key);if(!section){{section=document.createElement('section');section.className='page';section.innerHTML='<div class="placeholder"></div>'}}
       section.dataset.page=page.number;section.dataset.key=page.key;section.dataset.width=page.width;section.dataset.height=page.height;section.style.aspectRatio=page.width+'/'+page.height;fragment.appendChild(section);visible.observe(section)}}
     pages.replaceChildren(fragment);renderSelection();
   }}catch(_e){{}}
 }}
 function renderSelection(){{const selection=pendingSelection;if(!selection)return;const existing=pages.querySelector('.selection-line');if(selection.token===shownSelectionToken&&existing)return;const page=pages.querySelector('[data-page="'+selection.page+'"]');if(!page)return;loadPage(page).then(()=>{{if(pendingSelection?.token!==selection.token)return;pages.querySelectorAll('.selection-line').forEach(node=>node.remove());const regions=[...page.querySelectorAll('.semantic.text')].map(node=>({{x:Number(node.dataset.x),y:Number(node.dataset.y),width:Number(node.dataset.width),height:Number(node.dataset.height)}})).filter(region=>Object.values(region).every(Number.isFinite));let bounds;if(regions.length){{const seed=regions.reduce((best,region)=>Math.abs(selection.y-(region.y+region.height/2))<Math.abs(selection.y-(best.y+best.height/2))?region:best);const line=regions.filter(region=>Math.min(region.y+region.height,seed.y+seed.height)-Math.max(region.y,seed.y)>Math.min(region.height,seed.height)*.35);bounds={{x:Math.min(...line.map(region=>region.x)),y:Math.min(...line.map(region=>region.y)),right:Math.max(...line.map(region=>region.x+region.width)),bottom:Math.max(...line.map(region=>region.y+region.height))}}}}else{{const height=12;bounds={{x:0,y:Math.max(0,selection.y-height/2),right:Number(page.dataset.width),bottom:selection.y+height/2}}}}const node=document.createElement('div');node.className='selection-line';Object.assign(node.style,{{left:(bounds.x/Number(page.dataset.width)*100)+'%',top:(bounds.y/Number(page.dataset.height)*100)+'%',width:((bounds.right-bounds.x)/Number(page.dataset.width)*100)+'%',height:((bounds.bottom-bounds.y)/Number(page.dataset.height)*100)+'%'}});page.appendChild(node);shownSelectionToken=selection.token;window.__typstPreviewLastSelectionToken=selection.token}})}}
 pages.addEventListener('click',event=>{{const internal=event.target.closest?.('[data-target-page]');if(!internal)return;event.preventDefault();event.stopImmediatePropagation();const target=pages.querySelector('[data-page="'+internal.dataset.targetPage+'"]');target?.scrollIntoView({{behavior:'smooth',block:'center'}})}} ,true);
 pages.addEventListener('pointerdown',event=>{{drag={{x:event.clientX,y:event.clientY,scrollY}}}},true);
 addEventListener('pointermove',event=>{{if(!drag)return;const dx=event.clientX-drag.x,dy=event.clientY-drag.y;if(Math.hypot(dx,dy)>4){{drag.moved=true;document.body.classList.add('dragging');scrollTo(0,drag.scrollY-dy)}}}},true);
 addEventListener('pointerup',event=>{{if(drag?.moved){{event.preventDefault();event.stopImmediatePropagation()}}drag=null;document.body.classList.remove('dragging')}},true);
 window.typstPreview={{
   setScale:v=>{{scale=Math.max(.2,Math.min(1,Number(v)||1));pages.style.setProperty('--preview-scale',scale);save()}},
   setInvert:v=>{{document.body.classList.toggle('invert',!!v);save()}},
   showPage:n=>pages.querySelector('[data-page="'+n+'"]')?.scrollIntoView({{block:'start'}}),
   showSelection:(token,n,x,y)=>{{pendingSelection={{token,page:n,x,y}};renderSelection()}},
   showPosition:(n,x,y)=>{{const page=pages.querySelector('[data-page="'+n+'"]');page?.scrollIntoView({{behavior:'smooth',block:'center'}});window.typstPreview.showSelection(Date.now(),n,x,y)}},
   showPositions:positions=>{{let best=null,distance=Infinity;const center=scrollY+innerHeight/2;for(const position of positions||[]){{const page=pages.querySelector('[data-page="'+position.page+'"]');if(!page)continue;const point=page.offsetTop+(position.y/Number(page.dataset.height))*page.offsetHeight;const next=Math.abs(point-center);if(next<distance){{distance=next;best=position}}}}if(best)window.typstPreview.showPosition(best.page,best.x,best.y)}},
   refresh,state:()=>({{scale,scrollY}}),restore:s=>{{if(s){{window.typstPreview.setScale(s.scale||1);scrollTo(0,s.scrollY||0)}}}}
 }};
 addEventListener('scroll',save,{{passive:true}});try{{window.typstPreview.restore(JSON.parse(sessionStorage.getItem('typst-preview-state')))}}catch(_e){{}}
 refresh();setInterval(refresh,300);
}})();
</script>"#
    )
}

fn token(workspace_id: &str) -> String {
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
