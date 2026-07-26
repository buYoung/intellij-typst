// Intentionally invalid Typst. Each case is isolated by a paragraph boundary so parser recovery
// ranges can be asserted without one malformed construct consuming the next case.

// EXPECT: Expected a binding name
#let = 1

// EXPECT: Unmatched ')'
#)

// EXPECT: unresolved local symbol
#missing-symbol

// EXPECT: unresolved label
See @missing-label.

// EXPECT: unresolved import
#import "missing.typ": missing

// EXPECT: Unknown named argument `colums`
#table(colums: 2)

// EXPECT: incomplete call remains recoverable through end of file
#table(columns: 2,
