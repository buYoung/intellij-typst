// ============================================================
// Typst 플러그인 검증 파일 (typst-lang-core-02)
// 각 섹션에서 하이라이팅/오류(빨간 밑줄 "Unexpected token") 여부를 확인하세요.
// ============================================================

// ---- [원래 보고 버그] 이제 오류가 없어야 함 (전체 파싱 + 편집 시) ----
#let prefix = if "prefix" in data { data.prefix } else { "" }

// ---- [P2/P3 수정] 다중 행 코드: 커서를 안에 두고 타이핑해도 오류/하이라이팅 안 깨져야 함 ----
#let xs = (
  1,
  2,
  3,
)

#let build(data) = {
  let name = data.name
  let count = if "count" in data { data.count } else { 0 }
  [#name has #count items]
}

#if true {
  "yes"
} else {
  "no"
}

// ---- [리뷰 발견 B1 · 블로커] 인라인 변수 뒤 구두점 — 현재 "Unexpected token" 이 뜰 수 있음 ----
The values are #a, #b, and #c.
See #ref; also #value: and #x!

// ---- [리뷰 발견 B2 · 블로커] content block이 #ident 로 끝남 / 인라인 math 코드 ----
#emph[강조된 #x 부분]
결과: $#x$ 그리고 이어지는 문단.

// ---- [리뷰 발견 M1 · 주요] 인라인 조건/반복 뒤 같은 줄 산문 ----
#if draft [초안입니다] else [완성본입니다] 그리고 계속됩니다.

// ---- [리뷰 발견 M2 · 주요] 산문 속 밑줄/별표 — 이탤릭/볼드로 오분류될 수 있음 ----
파일 test_data_set.csv 를 참조하세요. 계산은 2*3*4 입니다.

// ---- [정상 마크업] 제목/목록/강조/참조/링크 ----
= 제목 1
== 부제목

- 항목 하나
- 항목 둘
+ 번호 하나
+ 번호 둘

이것은 *굵게* 와 _기울임_ 입니다.
자세한 내용은 @intro 를 보세요. 링크: https://typst.app

// ---- [함수 호출 / 배열 / 딕셔너리 / 클로저] ----
#let add = (x, y) => x + y
#let config = (width: 10pt, height: 50%, debug: true)
#table(columns: 2, [a], [b], [c], [d])
