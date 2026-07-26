//! Code-mode syntax corpus for Typst 0.15.

= Code

#let literal-none = none
#let literal-auto = auto
#let literal-booleans = (true, false)
#let literal-integers = (0, 42, 0xff, 0o17, 0b1010)
#let literal-floats = (3.14, .5, 1e3, 2.5e-2)
#let literal-units = (1pt, 2mm, 3cm, 4in, 5em, 6fr, 90deg, 1rad, 50%)
#let literal-string = "escaped quote: \" and Unicode: \u{1f600}"
#let literal-label = <code-label>
#let literal-content = [content]
#let literal-raw = `raw`

#let array = (1, 2, 3)
#let singleton = (1,)
#let dictionary = (name: "Typst", "hyphen-key": 15, nested: (enabled: true))
#let spread-array = (0, ..array, 4)
#let spread-dictionary = (..dictionary, extra: "value")

#let add(x, y: 1) = x + y
#let collect(first, ..rest) = (first, rest.pos(), rest.named())
#let closure = (x, y) => x + y
#let discarded-closure = _ => 1
#let destructured-closure = ((left, right)) => left + right

#let (first, second) = (1, 2)
#let (head, ..middle, tail) = (1, 2, 3, 4)
#let (name, nested: nested-value, ..remaining) = spread-dictionary
#let (_, kept, _) = (1, 2, 3)

#let method-result = "a, b, c".split(", ").map(value => upper(value)).join(" / ")
#let chained-result = dictionary.nested.enabled
#let call-result = add(first, y: second)
#let spread-result = closure(..(3, 4))

#let assignments = {
  let left = 1
  let right = 2
  (left, right) = (right, left)
  left += 1
  left -= 1
  left *= 2
  left /= 2
  (left, right)
}

#let operators = (
  unary: (-1, +1, not false),
  arithmetic: (1 + 2, 3 - 1, 2 * 3, 6 / 2),
  comparison: (1 == 1, 1 != 2, 1 < 2, 1 <= 2, 2 > 1, 2 >= 1),
  logic: (true and true, false or true),
  membership: (1 in array, 9 not in array),
)

Code results: #call-result, #spread-result, #method-result, #chained-result,
#assignments, #operators.logic, #collect(1, 2, named: 3), #closure(1, 2),
#discarded-closure(none), #destructured-closure((3, 4)), #middle, #tail,
#nested-value, #remaining, #kept, #singleton, #literal-none, #literal-auto,
#literal-booleans, #literal-integers, #literal-floats, #literal-units,
#literal-string, #literal-label, #literal-content, #literal-raw, #spread-array.

Code label target. <code-label>
