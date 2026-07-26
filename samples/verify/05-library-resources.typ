//! Representative standard-library and resource syntax for Typst 0.15.

= Library and resources

#let csv-data = csv("resources/data.csv")
#let json-data = json("resources/data.json")
#let yaml-data = yaml("resources/data.yaml")
#let xml-data = xml("resources/data.xml")
#let raw-data = read("resources/data.json")

#let colors = (
  rgb("336699"),
  rgb(20, 40, 60),
  luma(70%),
  cmyk(0%, 20%, 40%, 10%),
  oklab(60%, 0.1, 0.1),
  oklch(60%, 0.1, 40deg),
)

#block(fill: colors.at(0).lighten(70%), inset: 6pt, radius: 3pt)[
  #align(center)[Document model and layout]
  #columns(2, gutter: 8pt)[
    #box(width: 100%, stroke: 0.5pt + colors.at(1))[CSV rows: #csv-data.len()] \
    JSON: #json-data.name; YAML: #yaml-data.name; XML nodes: #xml-data.len().
  ]
]

#figure(
  image("resources/shape.svg", width: 30mm),
  caption: [An SVG resource],
) <resource-figure>

#table(
  columns: (auto, 1fr),
  stroke: 0.4pt + luma(60%),
  table.header([*Name*], [*Value*]),
  [alpha], [#csv-data.at(1).at(1)],
  [raw bytes], [#raw-data.len()],
)

See @resource-figure and cite @typst.

#bibliography("resources/references.bib", title: [References])
