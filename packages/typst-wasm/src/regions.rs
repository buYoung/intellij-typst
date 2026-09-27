use serde::Serialize;
use typst::layout::{Frame, FrameItem, Point, Transform};
use typst::model::Destination;
use typst::text::{BottomEdge, BottomEdgeMetric, TextEdgeBounds, TopEdge, TopEdgeMetric};
use crate::PagedDocument;
#[cfg(typst_v14)]
use typst::layout::Rect;
#[cfg(typst_v15)]
use typst::{foundations::AsOutput, introspection::{DocumentPosition, PagedPosition}};
#[cfg(not(typst_v15))]
use typst::layout::Position as PagedPosition;

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

pub(crate) fn semantic_regions(document: &PagedDocument, frame: &Frame) -> Vec<SemanticRegion> {
    let mut regions = Vec::new();
    collect_regions(document, frame, Affine::identity(), None, &mut regions);
    regions.sort_by_key(|region| match region.kind {
        "shape" => 0,
        "text" => 1,
        "link" => 2,
        _ => 0,
    });
    regions
}

fn collect_regions(
    document: &PagedDocument,
    frame: &Frame,
    transform: Affine,
    clip: Option<Bounds>,
    regions: &mut Vec<SemanticRegion>,
) {
    for (position, item) in frame.items() {
        match item {
            FrameItem::Group(group) => {
                let translated =
                    transform.then(Affine::translation(position.x.to_pt(), position.y.to_pt()));
                let group_clip = group
                    .clip
                    .as_ref()
                    .map(|curve| curve_bounds(curve).transformed(translated));
                let nested_clip = intersect_optional(clip, group_clip);
                if clip.is_some() && group_clip.is_some() && nested_clip.is_none() { continue }
                collect_regions(
                    document,
                    &group.frame,
                    translated.then(Affine::from_transform(group.transform)),
                    nested_clip,
                    regions,
                );
            }
            FrameItem::Text(text) => {
                let mut cursor_x = position.x.to_pt();
                for glyph in &text.glyphs {
                    let glyph_x = cursor_x + glyph.x_offset.at(text.size).to_pt();
                    #[cfg(typst_v14)]
                    let glyph_y = position.y.to_pt() - glyph.y_offset.at(text.size).to_pt();
                    #[cfg(not(typst_v14))]
                    let glyph_y = position.y.to_pt();
                    let (top, bottom) = text.font.edges(
                        TopEdge::Metric(TopEdgeMetric::Bounds),
                        BottomEdge::Metric(BottomEdgeMetric::Bounds),
                        text.size,
                        TextEdgeBounds::Glyph(glyph.id),
                    );
                    let rect = Bounds {
                        min_x: glyph_x,
                        min_y: glyph_y - top.to_pt(),
                        max_x: glyph_x + glyph.x_advance.at(text.size).to_pt(),
                        max_y: glyph_y + bottom.to_pt(),
                    };
                    push_region(regions, "text", rect.transformed(transform), clip, None);
                    cursor_x += glyph.x_advance.at(text.size).to_pt();
                }
            }
            FrameItem::Link(destination, size) => {
                let target = link_target(document, destination);
                push_region(
                    regions,
                    "link",
                    Bounds::from_position_size(*position, *size).transformed(transform),
                    clip,
                    Some(target),
                );
            }
            FrameItem::Shape(shape, _) => {
                let bounds = shape_bounds(shape).transformed(Affine::translation(position.x.to_pt(), position.y.to_pt()));
                push_region(
                    regions,
                    "shape",
                    bounds.transformed(transform),
                    clip,
                    None,
                );
            }
            FrameItem::Image(_, size, _) => push_region(
                regions,
                "shape",
                Bounds::from_position_size(*position, *size).transformed(transform),
                clip,
                None,
            ),
            FrameItem::Tag(_) => {}
        }
    }
}

struct LinkTarget {
    url: Option<String>,
    position: Option<PagedPosition>,
}

fn link_target(document: &PagedDocument, destination: &Destination) -> LinkTarget {
    match destination {
        Destination::Url(url) => LinkTarget {
            url: Some(url.to_string()),
            position: None,
        },
        Destination::Position(position) => LinkTarget {
            url: None,
            position: Some(*position),
        },
        Destination::Location(location) => LinkTarget {
            url: None,
            position: {
                #[cfg(typst_v15)]
                { document.as_output().introspector().position(*location).and_then(|position| match position {
                    DocumentPosition::Paged(position) => Some(position), _ => None,
                }) }
                #[cfg(not(typst_v15))]
                { Some(document.introspector.position(*location)) }
            },
        },
    }
}

fn push_region(
    regions: &mut Vec<SemanticRegion>,
    kind: &'static str,
    bounds: Bounds,
    clip: Option<Bounds>,
    target: Option<LinkTarget>,
) {
    let Some(bounds) = clip.map_or(Some(bounds), |clip| bounds.intersection(clip)) else {
        return;
    };
    if bounds.width() <= 0.1 || bounds.height() <= 0.1 || !bounds.is_finite() {
        return;
    }
    let position = target.as_ref().and_then(|target| target.position);
    regions.push(SemanticRegion {
        kind,
        x: bounds.min_x,
        y: bounds.min_y,
        width: bounds.width(),
        height: bounds.height(),
        url: target.and_then(|target| target.url),
        page: position.map(|position| position.page.get() as u32),
        target_x: position.map(|position| position.point.x.to_pt()),
        target_y: position.map(|position| position.point.y.to_pt()),
    });
}

#[derive(Clone, Copy)]
struct Affine {
    sx: f64,
    ky: f64,
    kx: f64,
    sy: f64,
    tx: f64,
    ty: f64,
}

impl Affine {
    fn identity() -> Self {
        Self {
            sx: 1.0,
            ky: 0.0,
            kx: 0.0,
            sy: 1.0,
            tx: 0.0,
            ty: 0.0,
        }
    }
    fn translation(tx: f64, ty: f64) -> Self {
        Self {
            tx,
            ty,
            ..Self::identity()
        }
    }
    fn from_transform(transform: Transform) -> Self {
        Self {
            sx: transform.sx.get(),
            ky: transform.ky.get(),
            kx: transform.kx.get(),
            sy: transform.sy.get(),
            tx: transform.tx.to_pt(),
            ty: transform.ty.to_pt(),
        }
    }
    fn then(self, next: Self) -> Self {
        Self {
            sx: self.sx * next.sx + self.kx * next.ky,
            ky: self.ky * next.sx + self.sy * next.ky,
            kx: self.sx * next.kx + self.kx * next.sy,
            sy: self.ky * next.kx + self.sy * next.sy,
            tx: self.sx * next.tx + self.kx * next.ty + self.tx,
            ty: self.ky * next.tx + self.sy * next.ty + self.ty,
        }
    }
    fn point(self, x: f64, y: f64) -> (f64, f64) {
        (
            self.sx * x + self.kx * y + self.tx,
            self.ky * x + self.sy * y + self.ty,
        )
    }
}

#[derive(Clone, Copy)]
struct Bounds {
    min_x: f64,
    min_y: f64,
    max_x: f64,
    max_y: f64,
}

impl Bounds {
    fn from_position_size(position: Point, size: typst::layout::Size) -> Self {
        Self {
            min_x: position.x.to_pt(),
            min_y: position.y.to_pt(),
            max_x: (position.x + size.x).to_pt(),
            max_y: (position.y + size.y).to_pt(),
        }
    }
    fn width(self) -> f64 {
        self.max_x - self.min_x
    }
    fn height(self) -> f64 {
        self.max_y - self.min_y
    }
    fn is_finite(self) -> bool {
        self.min_x.is_finite()
            && self.min_y.is_finite()
            && self.max_x.is_finite()
            && self.max_y.is_finite()
    }
    fn transformed(self, transform: Affine) -> Self {
        let points = [
            transform.point(self.min_x, self.min_y),
            transform.point(self.max_x, self.min_y),
            transform.point(self.max_x, self.max_y),
            transform.point(self.min_x, self.max_y),
        ];
        Self {
            min_x: points.iter().map(|p| p.0).fold(f64::INFINITY, f64::min),
            min_y: points.iter().map(|p| p.1).fold(f64::INFINITY, f64::min),
            max_x: points.iter().map(|p| p.0).fold(f64::NEG_INFINITY, f64::max),
            max_y: points.iter().map(|p| p.1).fold(f64::NEG_INFINITY, f64::max),
        }
    }
    fn intersection(self, other: Self) -> Option<Self> {
        let value = Self {
            min_x: self.min_x.max(other.min_x),
            min_y: self.min_y.max(other.min_y),
            max_x: self.max_x.min(other.max_x),
            max_y: self.max_y.min(other.max_y),
        };
        (value.min_x < value.max_x && value.min_y < value.max_y).then_some(value)
    }
}

fn curve_bounds(curve: &typst::visualize::Curve) -> Bounds {
    #[cfg(typst_v15)]
    { rect_bounds(curve.bbox(None)) }
    #[cfg(all(typst_v14, not(typst_v15)))]
    { rect_bounds(curve.bbox()) }
    #[cfg(not(typst_v14))]
    {
        use typst::visualize::CurveItem;
        let mut points = Vec::new();
        for item in &curve.0 {
            match item {
                CurveItem::Move(point) | CurveItem::Line(point) => points.push(*point),
                CurveItem::Cubic(a, b, c) => points.extend([*a, *b, *c]),
                CurveItem::Close => {},
            }
        }
        Bounds {
            min_x: points.iter().map(|point| point.x.to_pt()).fold(f64::INFINITY, f64::min),
            min_y: points.iter().map(|point| point.y.to_pt()).fold(f64::INFINITY, f64::min),
            max_x: points.iter().map(|point| point.x.to_pt()).fold(f64::NEG_INFINITY, f64::max),
            max_y: points.iter().map(|point| point.y.to_pt()).fold(f64::NEG_INFINITY, f64::max),
        }
    }
}

fn shape_bounds(shape: &typst::visualize::Shape) -> Bounds {
    #[cfg(typst_v15)]
    { rect_bounds(shape.bbox(true)) }
    #[cfg(not(typst_v15))]
    {
        use typst::visualize::Geometry;
        let mut bounds = match &shape.geometry {
            Geometry::Line(point) => Bounds { min_x: 0.0_f64.min(point.x.to_pt()), min_y: 0.0_f64.min(point.y.to_pt()),
                max_x: 0.0_f64.max(point.x.to_pt()), max_y: 0.0_f64.max(point.y.to_pt()) },
            Geometry::Rect(size) => Bounds::from_position_size(Point::zero(), *size),
            Geometry::Curve(curve) => curve_bounds(curve),
        };
        if let Some(stroke) = &shape.stroke {
            let radius = stroke.thickness.to_pt() / 2.0;
            bounds.min_x -= radius; bounds.min_y -= radius;
            bounds.max_x += radius; bounds.max_y += radius;
        }
        bounds
    }
}

#[cfg(typst_v14)]
fn rect_bounds(rect: Rect) -> Bounds {
    Bounds { min_x: rect.min.x.to_pt(), min_y: rect.min.y.to_pt(), max_x: rect.max.x.to_pt(), max_y: rect.max.y.to_pt() }
}

fn intersect_optional(first: Option<Bounds>, second: Option<Bounds>) -> Option<Bounds> {
    match (first, second) {
        (Some(first), Some(second)) => first.intersection(second),
        (Some(value), None) | (None, Some(value)) => Some(value),
        (None, None) => None,
    }
}

