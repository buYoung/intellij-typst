//! IDE feature anchors: navigation, completion, inlays, colors, structure, folding, formatting.

#set heading(numbering: "1.1")

= IDE features <ide-heading>

#let shadowed = "outer"
#let local-function(required, optional: 2, ..rest) = {
  let shadowed = "inner"
  let color-anchor = rgb("7f52ff")
  let folded = (
    first: required,
    second: optional,
    rest: rest.pos(),
  )
  box(fill: color-anchor.lighten(75%), inset: 4pt)[#shadowed: #folded]
}

#import "modules/exports.typ": exported-add

#let navigation-result = exported-add(10, 5)
#let builtin-navigation = counter("ide-counter")
#let math-navigation = $ arrow.r.long + pi $

#local-function(navigation-result, optional: 3, "sink item")
Go to the local label @ide-heading. Builtin: #builtin-navigation. Math: #math-navigation.

// Completion probes (place the caret after each prefix while testing manually):
// #tex
// #rgb("ff
// $ arrow.r. $
// #local-function(
