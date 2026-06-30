---
doc-type: Feature Design Doc
profile: full
feature-name: typst-syntax-highlighting
status: active
created: 2026-06-30
last-verified: 2026-06-30
verified-against: ff0cd7e
tags: [typst, syntax-highlighting, lexer, annotator, color-settings]
related:
  - docs/FDD/typst-native-language-services.md
  - docs/FDD/typst-formatting.md
purpose: Source of design decisions, not implementation actions
agent-readable: true
not:
  - task list
  - PR checklist
  - file-level change guide
---

# Typst Syntax Highlighting Feature Design Doc

## 1. Document Intent

이 문서는 Typst 구문 강조 기능의 사용자 경험, 범위, 색상 분류, 오류 표시 정책을 정하는 의사결정 출처다.

이 문서는 어떤 파일에 어떤 클래스를 만들지 지시하지 않는다. 구문 강조가 어떤 언어 개념을 사용자에게 어떻게 보여야 하는지를 정의한다.

---

## 2. Background / Problem

초기 플러그인 템플릿 상태에서는 Typst 파일이 일반 텍스트처럼 보인다. 사용자는 마크업, 코드 표현식, 수식, 문자열, 주석, 레이블, 함수 호출의 경계를 시각적으로 구분할 수 없다.

Typst는 문서 작성 언어이면서 프로그래밍 언어적 표현식을 포함한다. 같은 파일 안에서 자연어 텍스트, `#`로 시작하는 코드 표현식, 수식, 주석, 문자열이 번갈아 나타나므로 단일 색상이나 단순 키워드 강조만으로는 편집 맥락을 충분히 전달하기 어렵다.

구문 강조는 Typst 언어 지원의 첫 사용자 가시 기능이며, 이후 진단과 포매터가 공유할 토큰 분류의 품질을 검증하는 역할도 한다.

---

## 3. Feature Definition

```text
Typst Syntax Highlighting is the native editor coloring layer for Typst files.
```

### This feature is

- Typst 파일의 주요 문법 단위를 IDE 색상 체계에 맞춰 표시하는 기능이다.
- 빠른 렉서 기반 강조를 중심으로 한 편집 보조 기능이다.
- 사용자가 테마와 색상 설정을 통해 조정할 수 있어야 하는 에디터 표시 기능이다.

### This feature is not

- 문서 렌더링 미리보기나 PDF 스타일링 기능이 아니다.
- 모든 의미 오류를 검출하는 정적 분석 기능이 아니다.
- 외부 언어 서버에서 받은 토큰 색상 정보를 표시하는 기능이 아니다.
- 참조 해석, 정의 이동, 프로젝트 전체 진단을 소유하는 기능이 아니다.

---

## 4. Goals & Non-Goals

### Goals

- Typst의 마크업, 코드, 수식, 원시 텍스트, 문자열, 주석, 레이블, 참조, 제목, 목록 표식, 키워드, 구분 기호를 일관되게 구분한다.
- IDE 테마와 접근성 설정을 존중하는 기본 색상 키를 사용한다.
- 잘못된 문자나 복구 불가능한 토큰을 사용자에게 명확히 보여준다.
- 강조 규칙이 포매터와 언어 서비스의 문법 모델과 충돌하지 않게 한다.

### Non-Goals

- Typst 컴파일 결과의 시각적 스타일을 에디터 안에서 재현하지 않는다.
- 색상 강조만으로 참조 해석이나 타입 추론을 완성하려 하지 않는다.
- 외부 서버의 semantic token 결과와 1:1 호환성을 목표로 하지 않는다.
- 참조 해석 실패와 프로젝트 전체 의미 진단은 구문 강조 범위에서 다루지 않는다.

---

## 5. User Model & Core Concepts

### User Model

사용자는 색상 차이를 통해 “지금 작성 중인 텍스트가 문서 본문인지, 코드인지, 수식인지, 문자열인지”를 빠르게 판단한다.

사용자는 다음을 기대한다.

- 주석과 문자열은 본문과 명확히 구분된다.
- 코드 표현식과 일반 마크업은 시각적으로 다르다.
- 오류 또는 알 수 없는 문자는 놓치기 어렵게 표시된다.
- 테마를 바꿔도 강조가 깨지지 않는다.

사용자가 이해하지 않아도 되는 것은 다음과 같다.

- 렉서 기반 강조와 의미 기반 강조의 내부 차이
- 토큰 타입과 색상 키의 내부 매핑
- 복구 파싱이 어떻게 동작하는지

### Core Concepts

| Concept | Meaning |
| ------- | ------- |
| 토큰 | 구문 강조가 색상을 부여하는 최소 문법 단위 |
| 문맥 | Typst 파일 안에서 마크업, 코드, 수식, 원시 텍스트, 문자열, 주석 중 어느 영역인지 나타내는 상태 |
| 색상 범주 | 사용자가 색상 설정에서 조정할 수 있는 의미 있는 표시 그룹 |
| 잘못된 문자 | Typst 문법으로 분류할 수 없어 사용자에게 문제로 보여야 하는 입력 |
| 보조 표시 | 렉서만으로 표현하기 어려운 파일 내부의 저비용 문맥 표시 |
| 문맥 수정자 | 굵게, 기울임, 수식 같은 상위 문맥이 하위 토큰 표시를 보강하는 정보 |

---

## 6. Relationship to Existing Features

| Existing Feature | Relationship |
| ---------------- | ------------ |
| Typst Native Language Services | 같은 파일 타입과 토큰, 구문 개념을 공유한다. |
| Typst Formatting | 포매터가 사용하는 문법 경계와 강조 경계가 일관되어야 한다. |
| IDE Color Settings | 사용자가 색상 범주를 테마에 맞게 조정할 수 있어야 한다. |

---

## 7. Primary User Flows

### 7.1 Main Flow

```text
사용자가 .typ 파일을 연다
  -> IDE가 Typst 토큰을 빠르게 분류한다
  -> 사용자는 마크업, 코드, 수식, 주석, 문자열을 색상으로 구분하며 편집한다
```

### 7.2 Secondary Flow

```text
사용자가 IDE 색상 설정을 연다
  -> Typst 색상 범주를 확인하고 조정한다
  -> 에디터 강조가 선택한 테마와 사용자 설정을 따른다
```

### 7.3 Failure / Partial Success Flow

```text
사용자가 불완전한 Typst 구문을 입력한다
  -> 시스템은 가능한 앞뒤 토큰을 계속 강조한다
  -> 알 수 없는 입력은 제한된 범위에서만 문제로 표시된다
```

---

## 8. Design

### 8.1 Behavior

- 구문 강조는 즉시성 있는 렉서 기반 표시를 기본으로 한다.
- 보조 표시가 필요한 경우에는 읽기 전용, 상태 없음, 파일 내부 저비용 문맥만 사용하고, 무거운 프로젝트 해석은 강조 경로에 넣지 않는다.
- Typst 마크업과 코드 표현식의 전환 지점, 수식 문맥, 수식 안 코드 전환, 원시 텍스트, 문자열, 주석 경계는 사용자가 문맥을 오해하지 않도록 뚜렷하게 표시한다.
- 주석, 문서 주석, 문자열, 원시 텍스트, 본문 텍스트, 수식, 링크, 제목, 목록 표식, 레이블, 참조, 함수 후보, 네임스페이스 후보, 키워드, 불리언, 숫자, 구분 기호, 연산자, 이스케이프, 오류는 서로 다른 색상 범주로 분류할 수 있어야 한다.
- 굵게, 기울임, 수식 같은 상위 문맥은 별도 색상 강제보다 문맥 수정자로 표현해 테마와 사용자 색상 설정을 존중한다.
- 알 수 없는 문자는 조용히 무시하지 않고 IDE의 문제 표시 관례를 따른다.

### 8.2 Conceptual Data Model

| Entity | Meaning |
| ------ | ------- |
| Highlight Token | 색상 범주와 연결될 수 있는 Typst 토큰 |
| Highlight Context | 토큰이 마크업, 코드, 수식 중 어느 문맥에서 등장했는지 나타내는 정보 |
| Attribute Category | 사용자가 설정할 수 있는 색상 표시 범주 |
| Context Modifier | 상위 문맥이 토큰 표시를 보강하는 정보 |
| Error Highlight | 잘못된 문자나 복구 불가능한 구문을 표시하는 범위 |

| Field | Meaning |
| ----- | ------- |
| token kind | 문법 단위의 분류 |
| text range | 에디터에서 색상이 적용되는 범위 |
| fallback color | IDE 테마와 연결되는 기본 색상 의미 |
| severity | 문제 표시가 필요한 경우의 사용자 가시 강도 |
| modifier | 굵게, 기울임, 수식 같은 상위 문맥 표시 |

### 8.3 Failure Handling

- 렉서가 알 수 없는 입력을 만나면 해당 범위만 문제로 표시하고 이후 토큰화를 계속한다.
- 닫히지 않은 문자열, 수식, 코드 블록은 파일 끝까지 과도하게 오염시키지 않도록 복구 가능한 상태를 유지하고, 이후 문맥 전체를 잘못된 색으로 오염시키지 않는다.
- 보조 표시가 실패하면 기본 렉서 강조는 유지한다.
- 색상 설정 정보가 없거나 테마가 변경되어도 기본 IDE 색상 의미로 되돌아간다.

---

## 9. Policy Decisions

### 9.1 렉서 우선 강조 정책

Decision:

- 기본 구문 강조는 렉서 기반으로 제공한다.
- 보조 표시가 필요한 항목만 읽기 전용, 상태 없음, 파일 내부 저비용 분석을 사용한다.
- 보조 표시에는 파일 내부 함수 후보, 네임스페이스 후보, 레이블 표기처럼 실패해도 기본 강조를 유지할 수 있는 항목만 포함한다.

Rationale:

- 구문 강조는 타이핑 중 즉시 반응해야 하며 무거운 해석에 묶이면 안 된다.
- IntelliJ 플랫폼의 커스텀 언어 파이프라인은 렉서 기반 강조를 기본 계층으로 둔다.
- 참조 해석 실패와 검사성 진단은 Typst Native Language Services가 소유한다.
- 보조 표시 실패는 사용자에게 오류로 보이지 않고 기본 토큰 강조로 후퇴한다.

### 9.2 테마 친화 색상 정책

Decision:

- 색상은 하드코딩된 색이 아니라 IDE 기본 색상 의미와 사용자 설정 가능한 범주를 따른다.
- 문맥 수정자는 테마 호환성을 우선하며, 색상 범주보다 사용자 설정을 덜 방해하는 방식으로 적용한다.

Rationale:

- 사용자의 밝은 테마, 어두운 테마, 고대비 설정과 호환되어야 한다.
- 색상 설정을 제공하지 않으면 사용자가 Typst 강조를 자신에게 맞게 조정할 수 없다.

### 9.3 오류 표시 제한 정책

Decision:

- 잘못된 문자는 표시하되, 복구 가능한 불완전 입력을 과도한 오류로 확장하지 않는다.

Rationale:

- 문서 편집 중에는 닫히지 않은 괄호나 문자열이 자연스럽게 발생한다.
- 넓은 범위의 거짓 오류는 실제 문제를 찾기 어렵게 만든다.

### 9.4 Typst 문법 기준선 정책

Decision:

- v1 강조 기준선은 2026-06-30에 확인한 최신 `typst` crate 버전인 Typst 0.15.0 언어 표면을 따른다.
- 기준선 밖의 새 문법은 알 수 없는 토큰 또는 일반 텍스트로 제한 처리하고, 주변 강조를 가능한 한 유지한다.

Rationale:

- 강조는 언어 서비스와 포매터가 공유하는 토큰 분류의 첫 계층이다.
- 새 문법을 과감하게 오류 처리하면 정상 문서가 잘못된 파일처럼 보일 수 있다.

---

## 10. Alternatives Considered

### Alternative: 정규식 기반 파일 전체 강조

Description:

- 파일 전체를 정규식으로 훑어 키워드와 특수 문자를 색칠한다.

Why not chosen:

- Typst는 문맥에 따라 같은 문자가 다른 의미를 가지므로 정규식만으로 안정적인 문맥 구분이 어렵다.
- 포매터와 언어 서비스가 공유할 문법 모델을 만들 수 없다.

### Alternative: 외부 서버의 semantic token 위임

Description:

- 외부 언어 서버가 계산한 의미 토큰을 받아 에디터에 표시한다.

Why not chosen:

- `tinymist`를 사용하지 않는다는 제약과 충돌한다.
- 외부 서버 상태와 IDE 강조 상태가 어긋날 수 있다.
- 기본 강조가 외부 프로세스 생존 여부에 의존하게 된다.

---

## 11. Cross-cutting Concerns

### 11.1 Security

- 구문 강조는 로컬 문서 내용을 읽고 토큰화하는 범위로 제한한다.
- 외부 명령 실행이나 네트워크 호출은 포함하지 않는다.

### 11.2 Privacy

- 강조 계산을 위해 문서 내용이 IDE 밖으로 나가지 않는다.
- 사용자 소스 내용 수집이나 전송은 현재 범위에 포함하지 않는다.

### 11.3 Permissions

- 읽기 전용 분석이 기본이며, 사용자 문서를 자동 수정하지 않는다.
- 색상 설정은 IDE 사용자 설정 체계 안에서만 변경된다.

### 11.4 Observability

- 개발 중에는 알 수 없는 토큰 과다 발생과 복구 실패를 확인할 수 있어야 한다.
- 일반 사용 중에는 강조 실패가 에디터 사용을 방해하지 않아야 한다.

### 11.5 Accessibility

- 색상만으로 문제 의미를 전달하지 않고 IDE의 표준 오류 표시와 결합한다.
- 색상 범주는 사용자가 테마와 접근성 요구에 맞게 바꿀 수 있어야 한다.

### 11.6 Internationalization

- 색상 설정에 표시되는 범주 이름은 리소스 번들로 현지화할 수 있어야 한다.
- 문서 본문의 유니코드 문자와 다국어 텍스트는 강조 과정에서 보존된다.

---

## 12. Scope

### In Scope for initial native Typst support (2026-06-30)

- `.typ` 파일의 기본 Typst 구문 강조
- 마크업, 코드, 수식, 원시 텍스트, 문자열, 주석, 문서 주석, 레이블, 참조, 링크, 제목, 목록 표식, 키워드, 불리언, 숫자, 구분 기호, 연산자, 이스케이프, 오류 색상 범주
- 파일 내부 저비용 보조 표시와 문맥 수정자
- 잘못된 문자 또는 복구 불가능한 토큰의 제한적 표시
- 사용자가 조정 가능한 색상 설정 범주

### Out of Scope for initial native Typst support (2026-06-30)

- 문서 렌더링 결과와 동일한 시각 스타일 재현
- 외부 언어 서버 semantic token 연동
- 프로젝트 전체 의미 분석에 의존하는 고비용 강조
- 타입 또는 스코프 기반의 프로젝트 전체 의미 색상
- 사용자가 작성한 Typst 스타일을 에디터 색상으로 해석하는 기능

---

## 13. Risks & Open Questions

### Risks

- Typst의 문맥 전환을 충분히 복구하지 못하면 강조가 파일 후반부까지 잘못 이어질 수 있다.
- 색상 범주를 너무 세분화하면 사용자가 설정하기 어렵고 유지보수 부담이 커진다.
- 색상 범주를 너무 단순화하면 문서 언어와 코드 언어의 혼합 특성이 드러나지 않는다.

### Open Questions

- 초기 색상 범주는 어느 정도까지 세분화할 것인가?
- 수식 모드와 코드 모드의 공통 토큰은 같은 색상 범주를 공유할 것인가?
- Typst 표준 함수 이름은 기본 토큰 강조로만 다룰지, 언어 서비스의 자동완성 및 진단과 함께 다룰지 결정이 필요하다.
- 파일 내부 보조 표시에서 함수 후보와 네임스페이스 후보를 얼마나 적극적으로 추정할지 결정이 필요하다.

---

## 14. Platform Design

### 14.1 Common Design

구문 강조는 IntelliJ 에디터 표시 체계와 테마 체계를 따른다. 플랫폼이 제공하는 기본 색상 의미를 우선 사용하고, 사용자가 설정할 수 있는 Typst 전용 범주를 노출한다.

### 14.2 IntelliJ Platform

강조는 상태 재사용 없이 호출마다 안전하게 동작해야 한다. 언어 식별자는 대소문자를 일관되게 유지해야 하며, 색상 설정 페이지가 누락되지 않아야 한다.

### 14.3 Theme and Accessibility

밝은 테마, 어두운 테마, 고대비 테마에서 모두 식별 가능한 기본값을 가져야 한다. 의미가 중요한 문제 표시는 색상 외의 IDE 표준 표시와 함께 제공되어야 한다.

---

## 15. Result Semantics

| State | Meaning | User-visible? |
| ----- | ------- | ------------- |
| Highlighted | 토큰이 Typst 색상 범주에 매핑되었다. | Yes |
| Plain | 특별한 색상 의미 없이 기본 텍스트로 표시된다. | Yes |
| Error highlighted | 알 수 없거나 잘못된 입력으로 제한 표시된다. | Yes |
| Auxiliary unavailable | 보조 표시가 실패했지만 기본 강조는 유지된다. | No |
| Context modified | 상위 문맥 수정자가 기본 토큰 표시를 보강한다. | Yes |
| Theme fallback | 사용자 설정이나 테마별 색상이 없어 기본 IDE 의미를 사용한다. | Yes |

---

## 16. Future Extensions

- 레이블, 참조, 정의에 대한 더 정교한 의미 색상
- 표준 라이브러리 심볼과 사용자 정의 심볼의 색상 구분
- 프로젝트 인덱스 기반 의미 색상
- 문서 구조와 접기 영역에 맞춘 시각 보조

---

## Appendix

### References

- Typst syntax reference: https://typst.app/docs/reference/syntax/
- jetbrains-plugin-development skill: `07_language_syntax_highlighting.md`
- jetbrains-plugin-development skill: `07_language_custom_language_diagnostics.md`

### Code Map (non-normative)

| Concept / Flow | Where it lived (as of `verified-against`) |
| -------------- | ----------------------------------------- |
| 플러그인 기본 확장 등록 위치 | `src/main/resources/META-INF/plugin.xml` |
| 리소스 번들 위치 | `src/main/resources/messages/` |
| 현재 템플릿 Kotlin 소스 루트 | `src/main/kotlin/com/livteam/typninja/` |
