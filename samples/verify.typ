// Typst 0.15 syntax and IDE verification entry point.
// Compile tests provide samples/verify/packages through --package-path.

#set document(title: "Typst 0.15 verification corpus")
#set page(margin: 18mm)
#set text(size: 9pt)
#set heading(numbering: "1.1")

#import "@preview/example:0.1.0": add
#let package-result = add(2, 7)

= Typst 0.15 verification corpus

The local package fixture result is #package-result.

#include "verify/00-markup.typ"
#include "verify/01-code.typ"
#include "verify/02-control.typ"
#include "verify/03-modules.typ"
#include "verify/04-math.typ"
#include "verify/05-library-resources.typ"
#include "verify/06-ide.typ"
