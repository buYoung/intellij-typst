//! Module syntax corpus for Typst 0.15.

#set heading(numbering: "1.1")

= Modules

#import "modules/exports.typ"
#import "modules/exports.typ" as aliased-module
#import "modules/exports.typ": exported-value
#import "modules/exports.typ": exported-add as renamed-add
#import str: len as nested-len
#import "modules/more-exports.typ": extra-value, extra-function
#import "modules/more-exports.typ": *

#include "modules/included.typ"

Module values: #exports.exported-value, #aliased-module.exported-add(1, 2),
#exported-value, #renamed-add(2, 3), #nested-len("nested"), #extra-value,
#extra-function("module"). See @included-label.
