//! Markup syntax corpus for Typst 0.15.
/// A document comment remains lexically distinct from ordinary prose.

#set heading(numbering: "1.1")

= Markup
== Heading level two
=== Heading level three <markup-heading>

This is a paragraph with *strong*, _emphasis_, `inline raw`,
https://typst.app/, a label reference @markup-heading, and smart quotes:
'single' and "double". A forced line break follows. \
The next line contains shorthands: ~ -- --- ... and an escaped \#hash,
escaped \$dollar, and Unicode \u{1f600}.

- outer bullet
  - nested bullet
  + nested numbered item
- second bullet

+ numbered item
  / Term: nested term description
+ another numbered item

/ Syntax: A finite grammatical form.
/ Corpus: A collection of verification inputs.

```typ
#let raw-example = 1
```

// A line comment.
/* An outer block comment.
   /* A nested block comment. */
   The outer comment continues. */

Markup can switch to code with #(1 + 2), and code can return [*content*].
