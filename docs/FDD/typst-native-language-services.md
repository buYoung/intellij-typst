---
doc-type: Feature Design Doc
profile: full
feature-name: typst-native-language-services
status: active
created: 2026-06-30
last-verified: 2026-06-30
verified-against: ff0cd7e
tags: [typst, language-services, language-intelligence, psi, native-intellij]
related:
  - docs/FDD/typst-syntax-highlighting.md
  - docs/FDD/typst-formatting.md
purpose: Source of design decisions, not implementation actions
agent-readable: true
not:
  - task list
  - PR checklist
  - file-level change guide
---

# Typst Native Language Services Feature Design Doc

## 1. Document Intent

이 문서는 Typst 파일을 IntelliJ 기반 IDE 안에서 편집할 때 제공할 네이티브 언어 지능 기능의 의사결정 출처다.

사용자 요청의 `lsp` 범위는 Language Server Protocol 구현이 아니라, IDE 사용자가 기대하는 Typst 언어 지능으로 해석한다. 이 기능은 외부 언어 서버, JSON-RPC 통신, IntelliJ 플랫폼의 실험적 LSP 계층을 사용하지 않는다.

구현 순서, 클래스 배치, 파일별 작업 계획은 이 문서의 범위가 아니며, 별도 구현 계획에서 파생되어야 한다.

---

## 2. Background / Problem

현재 저장소는 초기 IntelliJ 플러그인 템플릿 상태이며 Typst 파일을 언어로 인식하는 사용자 경험이 없다. 사용자는 `.typ` 파일을 열 수 있더라도 Typst 문법 단위, 심볼, 참조, 오류, 자동완성, 이동 같은 편집 보조를 IDE가 이해하지 못한다.

Typst 문서는 마크업, 수식, 코드 표현식이 한 파일 안에서 섞이는 문서 언어다. 단순 텍스트 편집만으로는 문서 작성 중 잘못된 참조, 닫히지 않은 구문, 잘못된 함수 호출 위치, 정의로 이동 같은 기본 편집 흐름을 충분히 지원하기 어렵다.

이 기능은 외부 언어 서버 없이 IntelliJ 플랫폼의 언어 파이프라인 위에서 Typst 파일을 일급 언어로 다루기 위해 필요하다.

---

## 3. Feature Definition

```text
Typst Native Language Services is the native IntelliJ language-intelligence layer for Typst files.
```

### This feature is

- Typst 파일을 IDE가 언어 단위로 이해하게 만드는 네이티브 IntelliJ 언어 서비스 기능이다.
- `.typ` 파일의 파일 타입, 언어 식별, 구문 트리, 참조, 진단, 자동완성, 이동 경험의 기준점이다.
- 구문 강조와 포매터가 공유할 Typst 문법 모델의 상위 기능이다.

### This feature is not

- Language Server Protocol 구현체나 외부 언어 서버 실행 기능이 아니다.
- IntelliJ 플랫폼 LSP 계층에 Typst 서버를 등록하는 기능이 아니다.
- `tinymist`를 감싼 어댑터가 아니다.
- Typst 컴파일러, 렌더러, PDF 미리보기 기능이 아니다.
- Typst 패키지 매니저나 원격 패키지 동기화 기능이 아니다.

---

## 4. Goals & Non-Goals

### Goals

- Typst 파일을 IntelliJ 언어 파이프라인에서 독립 언어로 인식한다.
- 사용자가 문서를 작성할 때 기본 심볼 탐색, 참조 해석, 오류 표시, 자동완성을 받을 수 있게 한다.
- 문맥별 자동완성, 진단, 정의 이동, 참조, 문서화, 문서 구조, 패키지 및 import 보조를 하나의 일관된 언어 경험으로 묶는다.
- 구문 강조와 포매터가 같은 Typst 토큰 및 구문 개념을 공유하도록 한다.
- 외부 프로세스 없이 동작하여 설치, 성능, 오프라인 사용성을 단순하게 유지한다.

### Non-Goals

- Language Server Protocol 호환성을 제공하지 않는다.
- `tinymist`의 기능을 그대로 복제하거나 실행 결과와 완전 동일하게 맞추지 않는다.
- Typst 문서 렌더링 결과를 IDE 안에서 미리보는 기능은 다루지 않는다.
- 모든 Typst 표준 라이브러리 타입 시스템을 v1 범위에서 완전하게 모델링하지 않는다.

---

## 5. User Model & Core Concepts

### User Model

사용자는 이 기능을 “Typst 파일에서도 다른 언어처럼 IDE가 문맥을 이해한다”는 경험으로 받아들인다.

사용자는 다음을 기대한다.

- `.typ` 파일을 열면 IDE가 Typst 문서로 인식한다.
- 문서 안의 정의, 참조, 함수 호출, 레이블, 인용 대상이 가능한 범위에서 연결된다.
- 잘못된 문법이나 해석할 수 없는 참조가 편집 중 표시된다.
- 외부 도구를 따로 설치하지 않아도 기본 언어 지원이 동작한다.

사용자가 이해하지 않아도 되는 것은 다음과 같다.

- IntelliJ 내부의 PSI 모델과 인덱스 구조
- 외부 언어 서버와 네이티브 언어 파이프라인의 차이
- 구문 강조, 포매터, 자동완성이 어떤 확장 지점으로 나뉘는지

### Core Concepts

| Concept | Meaning |
| ------- | ------- |
| Typst 파일 | `.typ` 확장자를 가진 Typst 문서 소스 |
| 문법 모델 | 마크업, 수식, 코드 표현식, 문자열, 주석, 레이블 같은 Typst 언어 구조의 IDE 내부 표현 |
| 해석 문맥 | 현재 위치가 마크업, 코드, 수식, 원시 텍스트, 문자열, 주석 중 어느 규칙으로 해석되는지 나타내는 상태 |
| 심볼 | 문서 안에서 정의되거나 참조되는 이름, 레이블, 함수, 변수, 패키지 항목 |
| 진단 | 사용자에게 표시되는 구문 오류, 해석 실패, 제한된 의미 분석 결과 |
| 안전 범위 | 문자 경계를 보존하고 원본 문서 범위로 되돌릴 수 있는 분석 또는 편집 범위 |
| 네이티브 언어 서비스 | IntelliJ 플랫폼 API 위에서 직접 제공되는 언어 지능 |

---

## 6. Relationship to Existing Features

| Existing Feature | Relationship |
| ---------------- | ------------ |
| 초기 플러그인 템플릿 | Typst 언어 기능이 들어갈 빈 플러그인 골격으로 사용된다. |
| Typst Syntax Highlighting | 같은 파일 타입과 문법 모델을 사용하며, 언어 인식의 첫 사용자 가시 기능이다. |
| Typst Formatting | 같은 구문 트리를 사용해 문서 구조 기반 정렬과 간격 정책을 적용한다. |

---

## 7. Primary User Flows

### 7.1 Main Flow

```text
사용자가 .typ 파일을 연다
  -> IDE가 파일을 Typst 언어로 인식한다
  -> 사용자는 기본 진단, 자동완성, 이동, 참조 연결을 받으며 문서를 편집한다
```

### 7.2 Secondary Flow

```text
사용자가 문서 안의 레이블이나 식별자로 이동을 요청한다
  -> 시스템은 현재 프로젝트 안에서 해석 가능한 정의를 찾는다
  -> 해석에 성공하면 대상 위치로 이동하고, 실패하면 기본 IDE 동작으로 안전하게 물러난다
```

### 7.3 Failure / Partial Success Flow

```text
문서가 불완전하거나 Typst 문법 일부를 아직 지원하지 않는다
  -> 시스템은 가능한 토큰과 구문 범위까지만 해석한다
  -> 사용자는 편집을 계속할 수 있고, 지원되지 않는 구간은 과도한 오류 없이 제한적으로 표시된다
```

---

## 8. Design

### 8.1 Behavior

- `.typ` 파일은 Typst 언어 파일로 인식되어 기본 텍스트 파일과 다른 편집 경험을 제공한다.
- 문법 모델은 루트 문서를 마크업 문맥으로 시작하고, 코드 표현식, 수식, 원시 텍스트, 문자열, 주석으로 진입하는 전환을 명시적으로 반영한다.
- 코드 표현식 안의 콘텐츠 블록과 수식 안의 코드 전환처럼 문맥이 다시 바뀌는 위치는 별도 해석 규칙과 스코프를 가진다.
- 진단은 빠르고 국소적인 판단을 우선하며, 문서 전체나 프로젝트 전체 해석이 필요한 경우에는 지연되거나 제한될 수 있다.
- 자동완성은 현재 위치의 문맥을 기준으로 Typst 키워드, 알려진 심볼, 레이블, 인용 대상, 함수 인자, 필드 접근, import 경로, 파일 내부 정의를 조합한다.
- 참조 해석은 확실한 대상이 있는 경우에만 연결하며, 애매한 경우 잘못된 단일 대상으로 강제하지 않는다.
- 문서화는 서명, 타입에 준하는 설명, 사용자 정의 문서, 패키지 또는 import 상태를 가능한 범위에서 합성하되 실패한 일부 정보가 전체 표시를 막지 않는다.
- 문서 구조와 작업 공간 심볼은 제목, 레이블, 함수, 변수 같은 사용자가 탐색 가능한 개념을 우선 노출한다.

### 8.2 Conceptual Data Model

| Entity | Meaning |
| ------ | ------- |
| Typst Source File | 하나의 Typst 문서 소스와 그 언어 루트 |
| Typst Element | 문서 안의 마크업, 코드, 수식, 주석, 문자열, 심볼 같은 구문 단위 |
| Typst Context | 같은 텍스트라도 다르게 해석될 수 있는 문맥 상태 |
| Typst Symbol | 이름으로 정의되거나 참조되는 사용자 또는 표준 항목 |
| Typst Reference | 사용 위치에서 정의 위치로 연결될 수 있는 관계 |
| Typst Diagnostic | 사용자가 편집 중 확인할 수 있는 문제 표시 |
| Typst Import Surface | 파일 경로, 패키지 경로, import 항목을 포함하는 경로 기반 언어 표면 |

| Field | Meaning |
| ----- | ------- |
| range | 사용자에게 표시하거나 이동할 원본 문서 범위 |
| kind | 구문 단위나 심볼의 분류 |
| name | 해석 가능한 심볼 이름 또는 레이블 이름 |
| confidence | 해석 결과가 확정인지, 애매한지, 실패했는지의 설계상 구분 |
| context | 마크업, 코드, 수식, 원시 텍스트, 문자열, 주석 중 현재 해석 문맥 |

### 8.3 Failure Handling

- 불완전한 문법은 편집 중 정상 상태로 취급하고, 가능한 주변 구조만 보존한다.
- 알 수 없는 토큰은 사용자가 문제 위치를 볼 수 있게 표시하되, 이후 구간 전체를 무의미하게 오염시키지 않는다.
- 참조 대상, 자동완성 후보, 문서화 내용이 없으면 오류 알림보다 빈 결과를 우선한다.
- 진단 일부를 계산하지 못해도 나머지 진단은 유지하고, 문제가 해소된 범위에는 오래된 표시가 남지 않게 한다.
- 프로젝트가 인덱싱 중인 경우에는 인덱스 의존 기능을 늦추고, 파일 내부의 값만으로 가능한 기능은 계속 제공한다.

---

## 9. Policy Decisions

### 9.1 네이티브 우선 정책

Decision:

- Typst 언어 서비스는 IntelliJ 네이티브 언어 API를 기준으로 설계한다.
- Language Server Protocol 서버 실행, JSON-RPC 통신, IntelliJ 플랫폼 LSP 계층, `tinymist` 의존은 현재 범위에서 제외한다.

Rationale:

- 사용자가 명시적으로 `tinymist`를 사용하지 않겠다고 정했다.
- 네이티브 파이프라인은 구문 강조, 포매터, 참조, 자동완성, 리팩터링을 같은 문법 모델 위에서 통합할 수 있다.
- 외부 프로세스 의존을 제거하면 설치와 오프라인 사용성이 단순해진다.

### 9.2 부분 지원 정책

Decision:

- v1은 정확하지 않은 전체 언어 해석보다 안정적인 부분 지원을 우선한다.
- 지원하지 않는 문법 영역은 오탐 진단보다 안전한 미해석 상태를 선호한다.
- 애매한 문맥은 강하게 해석하지 않고 빈 결과 또는 원문 보존 상태로 둔다.

Rationale:

- Typst는 마크업과 코드 표현식이 섞여 있어 초기 파서가 모든 문법을 완전하게 다루기 어렵다.
- 편집기는 문서 작성 중 불완전한 상태를 자주 만나므로 복구 가능한 모델이 중요하다.

### 9.3 파서 세부 노드 은닉 정책

Decision:

- 사용자 기능과 다른 FDD는 세부 파서 노드가 아니라 문맥, 심볼, 참조, 진단, 안전 범위 같은 안정적인 언어 서비스 개념을 기준으로 삼는다.
- 내부 구문 트리가 바뀌어도 자동완성, 이동, 강조, 포매터가 공유하는 개념 이름은 가능한 한 유지한다.

Rationale:

- Typst 문법은 문맥 전환이 잦아 세부 노드가 사용자 기능 경계와 항상 일치하지 않는다.
- 안정적인 중간 개념을 두면 구문 강조와 포매터가 구현 세부에 과하게 결합하지 않는다.

### 9.4 확실한 해석만 연결하는 정책

Decision:

- 참조, 이동, 자동완성은 확실한 근거가 있는 범위에서만 강하게 연결한다.
- 다중 후보나 애매한 해석은 사용자에게 잘못된 단일 정답처럼 보이지 않게 한다.

Rationale:

- 잘못된 이동과 잘못된 오류 표시는 언어 지원 신뢰도를 빠르게 떨어뜨린다.
- 정확도는 이후 인덱싱과 의미 분석이 강화되면 점진적으로 넓힐 수 있다.

### 9.5 Typst 문법 기준선 정책

Decision:

- v1 문법 기준선은 2026-06-30에 확인한 최신 `typst` crate 버전인 Typst 0.15.0 언어 표면을 따른다.
- 기준선 밖의 새 문법이나 모호한 문법은 지원되지 않는 구간으로 안전하게 처리하고, 전체 파일 해석을 실패시키지 않는다.

Rationale:

- 초기 네이티브 파이프라인은 안정적인 편집 경험이 우선이며, 외부 서버가 제공하는 최신 문법 추적에 의존하지 않는다.
- `typst` crate 0.15.0과 공식 문법 문서를 기준으로 삼으면 렉서, 파서, 강조, 포매터가 같은 언어 표면을 공유할 수 있다.

### 9.6 패키지와 import 오프라인 우선 정책

Decision:

- v1은 로컬 프로젝트와 이미 확인 가능한 로컬 패키지 정보만 언어 서비스 대상으로 삼는다.
- 온라인 패키지 레지스트리 조회, 원격 다운로드, 최신 버전 확인은 현재 범위에서 제외한다.
- import/include 경로는 정의 이동, 경로 자동완성, 이름 변경 보조를 우선하고, 경로 자체의 프로젝트 전체 참조 검색은 후속 범위로 둔다.

Rationale:

- 네트워크 의존을 제거하면 보안, 개인 정보, 오프라인 사용성이 단순해진다.
- 경로 참조 검색은 파일 이동, 패키지 루트, 가상 경로 정책이 안정된 뒤 넓히는 편이 안전하다.

---

## 10. Alternatives Considered

### Alternative: `tinymist` 기반 언어 서버 위임

Description:

- 기존 Typst 언어 서버를 실행하고 IDE는 LSP 클라이언트처럼 동작한다.

Why not chosen:

- 사용자가 `tinymist`를 사용하지 않겠다는 제약을 명시했다.
- 외부 프로세스 설치, 버전 차이, 서버 수명주기, 프로토콜 오류가 플러그인 경험의 일부가 된다.
- 구문 강조와 포매터를 네이티브 PSI 기반으로 통합하려는 방향과 맞지 않는다.

### Alternative: 텍스트 기반 최소 지원

Description:

- 파일 타입과 단순 키워드 강조만 제공하고 문법 트리나 참조 모델을 만들지 않는다.

Why not chosen:

- 자동완성, 이동, 진단, 포매터가 같은 언어 이해를 공유할 수 없다.
- 이후 기능 확장이 매번 별도 휴리스틱으로 흩어질 위험이 크다.

---

## 11. Cross-cutting Concerns

### 11.1 Security

- 외부 프로세스를 실행하지 않으며, Typst 소스 분석은 IDE 프로세스 안에서 프로젝트 파일을 읽는 범위로 제한한다.
- 원격 패키지 조회나 임의 명령 실행은 현재 범위에 포함하지 않는다.

### 11.2 Privacy

- 문서 내용은 기본적으로 로컬 IDE 안에서만 분석된다.
- 네트워크 전송이나 원격 분석은 현재 범위에 포함하지 않는다.

### 11.3 Permissions

- 일반 프로젝트 파일 읽기 권한만 전제로 한다.
- 쓰기는 사용자 명령으로 발생하는 IDE 편집 동작에 한정되어야 한다.

### 11.4 Observability

- 언어 서비스 실패는 사용자가 편집을 계속할 수 있게 낮은 소음으로 처리한다.
- 플러그인 개발 중에는 파서 복구 실패, 진단 과다 발생, 인덱스 의존 기능 지연을 식별할 수 있어야 한다.

### 11.5 Accessibility

- 진단과 강조는 IDE의 표준 에디터 표시 체계를 사용해 색상만으로 의미를 전달하지 않는다.
- 이동, 자동완성, 빠른 문서 흐름은 키보드 중심 IDE 사용성과 호환되어야 한다.

### 11.6 Internationalization

- 사용자에게 보이는 진단과 설정 문구는 리소스 번들로 옮길 수 있는 문장으로 유지한다.
- Typst 언어의 식별자와 텍스트는 유니코드 입력을 훼손하지 않아야 한다.

---

## 12. Scope

### In Scope for initial native Typst support (2026-06-30)

- `.typ` 파일을 Typst 언어 파일로 인식한다.
- 편집 중 복구 가능한 Typst 문법 모델을 제공한다.
- 파일 내부 중심의 기본 심볼, 식별자 및 레이블 참조, 자동완성, 진단, 문서화 경험을 제공한다.
- 문서 구조와 파일 내부 심볼 탐색을 제공한다.
- 로컬 import/include 경로의 정의 이동, 경로 자동완성, 이름 변경 보조를 제공한다.
- 구문 강조와 포매터가 같은 언어 개념을 공유할 수 있게 한다.

### Out of Scope for initial native Typst support (2026-06-30)

- Language Server Protocol 서버 구현 또는 클라이언트 연동
- IntelliJ 플랫폼 LSP 계층을 통한 서버 등록
- `tinymist` 실행, 감지, 설정, 버전 관리
- PDF 렌더링, 미리보기, 컴파일 로그 통합
- 전체 Typst 표준 라이브러리 타입 추론
- 원격 패키지 다운로드와 패키지 레지스트리 검색
- import/include 경로 자체의 프로젝트 전체 참조 검색
- 온라인 패키지 버전 상태 표시
- 수식 문맥의 전체 의미 해석과 타입 추론

---

## 13. Risks & Open Questions

### Risks

- Typst 문법 전체를 초기에 충분히 복구 가능하게 모델링하지 못하면 포매터와 참조 기능의 품질이 함께 낮아질 수 있다.
- 외부 언어 서버를 사용하지 않으므로 기존 생태계 기능과 결과가 다를 수 있다.
- 문법 모델을 너무 좁게 잡으면 이후 수식, 패키지, 표준 라이브러리 기능 확장이 어려워질 수 있다.

### Open Questions

- v1에서 지원할 Typst 표준 라이브러리 심볼 범위는 어디까지인가?
- 프로젝트 전체 참조 해석은 파일 내부 지원 이후 어느 범위까지 확장할 것인가?
- Typst 패키지 import와 로컬 패키지 경로를 어느 단계에서 언어 서비스에 포함할 것인가?
- Typst 표준 라이브러리 심볼을 수동 정의로 둘지, `typst` crate 0.15.0 메타데이터에서 추출할지 결정이 필요하다.

---

## 14. Platform Design

### 14.1 Common Design

기능은 IntelliJ 플랫폼의 커스텀 언어 파이프라인을 기준으로 한다. 모든 JetBrains IDE에서 동일한 `.typ` 파일 인식과 에디터 중심 언어 경험을 제공하는 것을 목표로 한다.

### 14.2 IntelliJ Platform

언어 서비스는 플랫폼의 파일 타입, 렉서, 파서, PSI, 참조, 자동완성, 주석, 포매터, 진단 확장 모델과 맞아야 한다. 확장 구현은 상태를 직접 들고 있지 않고, 필요한 상태는 프로젝트 수명주기와 맞는 서비스나 인덱스 모델로 분리한다.

### 14.3 Remote Development

원격 개발 환경에서는 에디터 반응성과 백엔드 분석 위치가 중요하다. 사용자에게 보이는 편집 기능은 지연을 최소화하고, 무거운 분석은 취소 가능해야 한다.

---

## 15. Result Semantics

| State | Meaning | User-visible? |
| ----- | ------- | ------------- |
| Supported | 해당 문서 범위를 Typst 문법과 의미로 해석했다. | Yes |
| Partially supported | 일부 구문은 해석했지만 지원하지 않는 영역이 남아 있다. | Yes |
| Empty result | 현재 문맥에서 제공할 후보, 이동 대상, 문서화 내용이 없어 조용히 결과를 비운다. | Yes |
| Unresolved | 참조나 심볼 대상이 확정되지 않았다. | Yes |
| Deferred | 인덱싱이나 취소 가능한 분석 때문에 결과를 늦춘다. | No |
| Stale cleared | 이전 진단이나 표시가 더 이상 유효하지 않아 비운 상태로 갱신된다. | Yes |
| Unsupported | 현재 범위에서 의도적으로 다루지 않는 Typst 기능이다. | Yes |

---

## 16. Future Extensions

- 프로젝트 전체 심볼 인덱싱
- Typst 표준 라이브러리와 패키지 메타데이터 기반 자동완성
- 수식 문맥의 의미 자동완성
- 문서 구조 보기와 빠른 탐색
- 리팩터링, 이름 변경, 사용처 찾기
- Typst 렌더링 및 미리보기 기능과의 통합

---

## Appendix

### References

- Typst syntax reference: https://typst.app/docs/reference/syntax/
- jetbrains-plugin-development skill: `07_language_pipeline.md`

### Code Map (non-normative)

| Concept / Flow | Where it lived (as of `verified-against`) |
| -------------- | ----------------------------------------- |
| 플러그인 식별자와 기본 의존성 | `src/main/resources/META-INF/plugin.xml` |
| 플랫폼 버전 선택 | `build.gradle.kts` |
| 템플릿 서비스 예시 | `src/main/kotlin/com/livteam/typninja/services/` |
