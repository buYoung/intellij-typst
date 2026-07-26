//! Control-flow and styling-rule syntax corpus for Typst 0.15.

= Control and rules

#let condition = 2
#let conditional-result = if condition == 1 {
  [one]
} else if condition == 2 [
  two
] else {
  [other]
}

#let for-result = for (index, value) in ("a", "b").enumerate() {
  if index == 0 { continue }
  if value == "stop" { break }
  [#value]
}

#let while-result = {
  let count = 0
  while count < 3 {
    count += 1
    if count == 2 { continue }
    [#count]
  }
}

#let classify(value) = {
  if value < 0 { return "negative" }
  "non-negative"
}

#let contextual-value = context text.lang
#set text(fill: rgb("202040")) if condition > 0
#show heading.where(level: 3): set text(fill: rgb("4050a0"))
#show emph: it => underline(it.body)
#show raw.where(block: false): it => box(fill: luma(95%), inset: 2pt, it)

#conditional-result #for-result #while-result #classify(1) #contextual-value
