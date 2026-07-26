//! Math-mode syntax corpus for Typst 0.15.

= Math

#let math-code-value = 5

Inline math $x_1^2 + y^2 = z^2$ and a block equation:

$ sum_(k=0)^n k
    &= 1 + ... + n \
    &= (n(n+1)) / 2 $

$ "area" = pi dot "radius"^2 $
$ arrow.r.long quad x -> y => x != y $
$ frac(a^2, 2) + floor(x) $
$ vec(1, 2, delim: "[") $
$ mat(1, 2; 3, 4) $
$ mat(..#range(1, 5).chunks(2)) $
$ #math-code-value < 17 $
$ #rect(width: 4mm, height: 2mm, fill: red) $
$ x\^2 + a\,b $
$ (a + b) / (c + d) $
