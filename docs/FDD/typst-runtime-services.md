---
doc-type: Feature Design Doc
profile: full
feature-name: typst-runtime-services
status: active
created: 2026-07-25
last-verified: 2026-07-25
verified-against: 46ee8ce
tags: [typst, runtime, diagnostics, packages, preview, svg]
related:
  - docs/FDD/typst-native-language-services.md
purpose: Source of design decisions, not implementation actions
agent-readable: true
not:
  - task list
  - PR checklist
  - file-level change guide
---

# Typst Runtime Services Feature Design Doc

## 1. Document Intent

이 문서는 Typst 실제 컴파일 진단, 패키지 확보, SVG 미리보기를 하나의 프로젝트별 컴파일 스냅샷으로 제공하는 런타임 서비스의 의사결정 출처다.

구현 순서와 파일별 변경 방법은 포함하지 않는다. 네이티브 IntelliJ PSI 언어 서비스의 역할과 런타임 보조 기능의 경계를 정의한다.

---

## 2. Background / Problem

보수적인 PSI 분석만으로는 Typst 컴파일러가 아는 오류 범위, 열린 문서의 미저장 내용을 반영한 렌더링, 원격 `@preview` 패키지 확보, 렌더 결과와 소스 위치의 관계를 정확히 제공하기 어렵다.

기존 CLI 미리보기는 매번 별도 프로세스를 실행하고 PNG 전체를 다시 읽기 때문에 증분 SVG 표시와 하나의 컴파일 세대에 묶인 진단을 제공하지 못한다. 또한 런타임 배포 실패가 기본 언어 지능까지 중단시키지 않아야 한다.

---

## 3. Feature Definition

```text
Typst Runtime Services is a project-scoped auxiliary runtime for compiler diagnostics, preview packages, and SVG preview snapshots.
```

### This feature is

- 네이티브 PSI 언어 서비스를 보완하는 선택적 프로젝트 런타임이다.
- 문서 버전과 컴파일 세대를 가진 요청·응답 경계다.
- 런타임 부재나 장애 시 Typst CLI 및 로컬 패키지 동작으로 후퇴하는 기능이다.

### This feature is not

- 네이티브 자동완성, 참조, 리팩터링의 대체 구현이 아니다.
- Language Server Protocol 서버나 클라이언트가 아니다.
- `tinymist` 실행 파일 또는 프로토콜 어댑터가 아니다.

---

## 4. Goals & Non-Goals

### Goals

- 저장 및 입력 중 실제 컴파일 진단을 정확한 문서 세대에 연결한다.
- 열린 Typst 문서의 메모리 내용을 하나의 컴파일 오버레이로 반영한다.
- 완성된 `@preview` import를 안전하게 자동 확보하고 로컬 언어 서비스에 연결한다.
- Typst 파일 편집기 안에서 소스·미리보기·분할 모드를 제공한다. JCEF 가능 환경에서는 링크 정보를 유지하는 반응형 SVG를 표시하고, 불가능한 환경에서는 명시적인 비활성 상태를 표시한다.
- 다운로드, 호환성, 충돌 실패가 네이티브 PSI 기능을 중단시키지 않게 한다.

### Non-Goals

- Tinymist의 문서 테스트, 벤치마크, 커버리지, 프로파일링 도구를 복제하지 않는다.
- PDF, PNG, SVG, HTML 내보내기 계약을 네이티브 런타임으로 이전하지 않는다.
- 원본 위치가 없는 생성 콘텐츠에 근사 소스 이동을 제공하지 않는다.

---

## 5. User Model & Core Concepts

### User Model

사용자는 이 기능을 “저장하거나 잠시 입력을 멈추면 실제 Typst 결과가 진단과 미리보기에 반영되고, 필요한 공개 패키지가 자동으로 준비된다”는 경험으로 이해한다.

사용자는 프로토콜 세대, 바이너리 캐시, 오버레이 디렉터리, 렌더러 구현을 알 필요가 없다.

### Core Concepts

| Concept | Meaning |
| ------- | ------- |
| 런타임 상태 | 바이너리 준비, 호환성, 실패, CLI 대체 여부 |
| 컴파일 세대 | 새 요청이 이전 결과를 폐기할 수 있게 하는 단조 증가 번호 |
| 문서 버전 | IDE 메모리 문서와 응답을 연결하는 버전 |
| 컴파일 스냅샷 | 오버레이, 진단, 페이지, 미리보기 URL이 같은 결과에서 나온 상태 |
| 패키지 상태 | 원격 사용 가능, 다운로드 중, 설치됨, 실패 |
| 소스 매핑 | UTF-16 소스 위치와 렌더 문서 좌표 사이의 검증된 관계 |

---

## 6. Relationship to Existing Features

| Existing Feature | Relationship |
| ---------------- | ------------ |
| Typst Native Language Services | 대체하지 않고 실제 컴파일 결과와 패키지 설치 상태를 보완한다. |
| Typst CLI Preview and Export | 런타임 실패 시 SVG 미리보기, 내보내기, 추가 CLI 인자 사용 시 대체 경로로 유지한다. |
| Local Package Catalog | 설치 완료 후 갱신되어 패키지 내부 이동과 완성을 활성화한다. |
| Typst Settings | 기존 루트, 메인 파일, 글꼴, 시스템 글꼴, 패키지 경로를 공유하며 새 정책 설정을 추가한다. |

---

## 7. Primary User Flows

### 7.1 Main Flow

```text
사용자가 Typst 문서를 저장하거나 입력을 잠시 멈춘다
  -> 시스템이 열린 문서 오버레이와 현재 설정으로 새 컴파일 세대를 요청한다
  -> 최신 세대의 진단과 SVG 미리보기만 IDE에 반영된다
```

### 7.2 Secondary Flow

```text
사용자가 완성된 @preview/name:version import를 입력한다
  -> 시스템이 공개 색인을 확인하고 안전한 임시 위치에 패키지를 받는다
  -> 원자 설치 후 패키지 카탈로그와 라이브러리 루트가 갱신된다
```

### 7.3 Failure / Partial Success Flow

```text
런타임이 없거나 호환되지 않거나 반복 충돌한다
  -> 시스템이 반복 재시작과 알림을 제한한다
  -> 내보내기와 미리보기는 기존 Typst CLI 경로를, 패키지는 로컬 카탈로그를 사용한다
  -> 네이티브 PSI 편집 기능은 계속 동작한다
```

---

## 8. Design

### 8.1 Behavior

- 프로젝트별 장기 실행 런타임은 표준 입력과 표준 출력의 단일 행 JSON 프로토콜 버전 1을 사용한다.
- 모든 요청과 응답은 요청 식별자, 작업 공간 식별자, 문서 버전, 컴파일 세대를 보존한다.
- 초기화, 소스 갱신, 컴파일, 양방향 위치 질의, 패키지 색인, 패키지 확보, 종료 연산을 구분한다.
- 추가 CLI 인자가 있으면 런타임이 이를 조용히 버리지 않고 기존 CLI 경로를 사용한다.
- 컴파일 실패 중에는 마지막 성공 미리보기를 지우지 않고 새 오류 상태만 표시한다.
- JCEF가 없으면 미리보기를 사용할 수 없다는 명시적인 상태를 표시한다.
- 위치 매핑이 검증되지 않은 결과는 `mapped=false`로 표시하고 이동 동작을 수행하지 않는다.

### 8.2 Conceptual Data Model

| Entity | Meaning |
| ------ | ------- |
| Runtime Manifest | 플랫폼별 URL, 바이트 크기, SHA-256, 프로토콜 버전 |
| Runtime Request | 상관관계 필드와 연산별 입력 |
| Compile Result | 세대, 문서 버전, 출력 상태, 파일별 진단, 페이지, 매핑 가능 여부 |
| Package Record | 명세, 원격 위치, 설치 위치, 다운로드 상태 |
| Preview Session | 루프백 주소, 예측 불가능한 토큰, 현재 SVG 페이지 |

| Field | Meaning |
| ----- | ------- |
| generation | 오래된 컴파일 결과를 폐기하는 번호 |
| documentVersion | IDE 문서와 결과의 일치 여부 |
| outputStatus | 성공, 실패, 취소 상태 |
| sourceMappingAvailable | 해당 렌더 결과에서 미리보기 클릭을 소스 위치로 연결해도 되는지 여부 |
| previewUrl | 루프백 토큰 URL |

### 8.3 Failure Handling

- 바이너리 다운로드는 크기와 SHA-256을 확인한 뒤에만 원자 캐시에 반영한다.
- 지원하지 않는 플랫폼이나 프로토콜은 호환되지 않음 상태가 되고 CLI로 후퇴한다.
- 반복 충돌은 세 번 이후 자동 재시작을 중단한다.
- 패키지 다운로드는 HTTPS, 크기 제한, 경로 이탈 및 링크 차단, 임시 압축 해제, 원자 이동을 적용한다.
- 오래된 세대나 문서 버전의 결과는 IDE 상태에 반영하지 않는다.

---

## 9. Policy Decisions

### 9.1 네이티브 PSI 보존

Decision:

- Rust 런타임은 컴파일, 패키지, 미리보기만 담당하며 PSI 자동완성, 참조, 리팩터링을 대체하지 않는다.

Rationale:

- 런타임 설치와 장애 여부에 상관없이 기본 편집 경험을 유지해야 한다.

### 9.2 자동 다운로드

Decision:

- 런타임과 `@preview` 패키지 자동 다운로드는 기본 활성화하되 각각 끌 수 있다.
- `@local`과 사용자 네임스페이스는 네트워크에서 받지 않는다.

Rationale:

- 기본 흐름은 별도 확인 없이 동작해야 하지만 네트워크 정책이 엄격한 환경에는 명시적 차단 수단이 필요하다.

### 9.3 진단 트리거

Decision:

- 컴파일 진단 기본값은 저장 시 실행이며, 입력 중 실행은 500ms 디바운스와 최신 세대 우선 정책을 사용한다.

Rationale:

- 실제 컴파일 결과를 기본 제공하면서 입력 지연과 불필요한 프로세스 사용을 제한한다.

### 9.4 내보내기 보존

Decision:

- 기존 미리보기와 내보내기 호출 계약을 유지하고, 모든 명시적 내보내기는 Typst CLI를 계속 사용한다.

Rationale:

- 형식별 호환성과 사용자 지정 CLI 인자를 보존해야 한다.

---

## 10. Alternatives Considered

### Alternative: Tinymist 또는 LSP

Description:

- 기존 언어 서버와 프로토콜에 컴파일과 미리보기를 위임한다.

Why not chosen:

- 사용자 제약상 Tinymist 실행 파일, 코드 표면, 프로토콜에 직접 의존하지 않아야 하고 네이티브 PSI 언어 서비스를 유지해야 한다.

### Alternative: CLI 전용 PNG 미리보기

Description:

- 기존처럼 모든 변경마다 CLI로 PNG 전체를 다시 생성한다.

Why not chosen:

- 하나의 컴파일 스냅샷에 진단, 패키지, 페이지 상태를 연결하거나 JCEF SVG 상태를 유지하기 어렵다.

---

## 11. Cross-cutting Concerns

### 11.1 Security

- 런타임 미리보기는 `127.0.0.1`과 세션 토큰으로만 노출하고 외부 탐색 및 외부 리소스를 콘텐츠 보안 정책으로 차단한다.
- 실행 파일과 패키지는 크기, SHA-256 또는 안전 압축 해제 경계를 통과한 뒤에만 영구 캐시에 들어간다.

### 11.2 Privacy

- 문서 내용과 렌더 결과는 로컬 IDE, 로컬 자식 프로세스, 루프백 연결 안에서만 처리한다.
- 네트워크 요청은 공개 런타임 자산과 Typst 공개 패키지 색인 및 아카이브에 한정한다.

### 11.3 Permissions

- 플러그인 시스템 캐시와 구성된 Typst 패키지 캐시에 쓰기 권한이 필요하다.
- 프로젝트 외부 임의 파일 읽기 권한을 미리보기 브리지에 노출하지 않는다.

### 11.4 Observability

- 사용자는 런타임 상태와 패키지 상태를 확인할 수 있고, 프로세스 오류는 IDE 로그에 남는다.
- 오래된 결과 폐기, 반복 충돌, CLI 대체 여부를 구분한다.

### 11.5 Accessibility

- 진단은 IDE 표준 심각도와 텍스트 메시지를 사용해 색상만으로 의미를 전달하지 않는다.
- JCEF가 없는 환경에도 미리보기 비활성 원인을 텍스트로 제공한다.

### 11.6 Internationalization

- 소스 위치는 0기반 UTF-16 줄·열을 사용해 IDE 문서 좌표와 유니코드 입력을 보존한다.
- 사용자 가시 상태와 오류 문구는 향후 리소스 번들로 지역화할 수 있어야 한다.

---

## 12. Scope

### In Scope for as implemented (2026-07-25)

- 네 플랫폼의 런타임 빌드와 릴리스 매니페스트
- 프로토콜 버전 1과 프로젝트 수명주기
- 저장 및 입력 중 컴파일 진단, 열린 문서 오버레이, CLI 진단 대체 경로
- `@preview` 색인과 안전한 자동 패키지 설치
- JCEF SVG 보기와 CLI SVG 대체 컴파일
- 기존 내보내기 계약

### Out of Scope for as implemented (2026-07-25)

- Tinymist 실행 파일, LSP, Tinymist 프로토콜
- Typst 문서 테스트와 성능 분석 도구 복제
- 내보내기의 런타임 전환

---

## 13. Risks & Open Questions

### Risks

- Reflexo 릴리스 후보가 선언한 Typst 범위와 실제 사용 API가 공개 Typst `0.15.x`와 어긋난다. Typst `0.15.0` 기준선을 유지하고 Reflexo 크레이트는 직접 렌더 경로가 호환될 때까지 비활성 선택 의존성으로 둔다.
- 대규모 작업 공간의 메모리 오버레이 복제 비용이 미리보기 지연을 만들 수 있다.
- 로컬 루프백이라도 브라우저 브리지 입력 검증이 약해지면 프로젝트 탐색 표면이 넓어질 수 있다.

### Open Questions

- 미리보기에서 소스로의 이동은 Typst `PagedDocument`의 실제 `Span`과 클릭 좌표를 사용한다. 반대 방향인 소스에서 미리보기로의 위치 질의는 아직 안전하게 매핑 불가를 반환한다.
- 런타임 컴파일 경로는 Typst `0.15.0` 라이브러리와 시스템 작업 공간을 직접 사용한다. 추가 CLI 인자가 설정된 경우에는 기존 CLI 경로를 유지한다.
- 변경 페이지만 전송하는 증분 프로토콜은 현재 미리보기 상태 보존 경계 위에서 후속 구현이 필요하다.
- `reflexo-* 0.8.0-rc3`의 공개 소스는 Typst `0.15.0`과 `0.15.1` 모두에서 비공개 PDF API를 요구해 직접 렌더 기능을 활성화할 수 없다. 정확한 버전은 선택 의존성으로 고정했지만 현재 실행 경로에는 포함되지 않는 알려진 편차다.

---

## 14. Platform Design

### 14.1 Common Design

모든 플랫폼은 동일한 프로토콜 버전, 상태 의미, 캐시 검증, CLI 대체 정책을 사용한다.

### 14.2 macOS

Apple Silicon과 Intel 바이너리를 별도 릴리스 자산으로 제공하고 실행 권한을 캐시 설치 시 적용한다.

### 14.3 Windows

64비트 MSVC 바이너리를 제공하고 실행 파일 확장자를 플랫폼 선택에 반영한다.

### 14.4 Linux

64비트 GNU 바이너리를 제공한다. ARM Linux는 현재 호환되지 않음 상태로 처리한다.

### 14.5 IntelliJ 편집기와 JCEF

Typst 파일은 별도 Tool Window가 아니라 IntelliJ의 결합 편집기에서 소스·미리보기·분할 모드를 전환한다. JCEF 지원 여부를 실행 시 확인하며, 지원 환경은 토큰화된 루프백 또는 CLI 대체 SVG 페이지를 표시하고 미지원 환경은 명시적인 비활성 상태를 표시한다.

---

## 15. Result Semantics

| State | Meaning | User-visible? |
| ----- | ------- | ------------- |
| 미설치 | 현재 플랫폼 런타임이 캐시에 없다. | Yes |
| 다운로드 중 | 검증 전 임시 파일로 런타임을 받고 있다. | Yes |
| 준비됨 | 프로토콜 호환 런타임이 실행 가능하다. | Yes |
| 호환되지 않음 | 플랫폼 또는 프로토콜 버전이 지원되지 않는다. | Yes |
| 실패 | 다운로드나 프로세스 실행이 실패했다. | Yes |
| CLI 대체 | 기존 Typst CLI 경로를 사용한다. | Yes |
| 원격에서 사용 가능 | 완성된 공개 패키지 명세를 감지했다. | Yes |
| 설치됨 | 패키지가 로컬 카탈로그에서 사용 가능하다. | Yes |
| 취소 | 더 최신 세대가 있어 결과를 반영하지 않는다. | No |

---

## 16. Future Extensions

- 소스 위치에서 미리보기 위치로의 역방향 동기화
- Reflexo 직접 컴파일과 변경 페이지 단위 전송
- 미리보기 진단 표시와 오류 위치 이동

---

## Appendix

### Code Map (non-normative)

| Concept / Flow | Where it lived (as of `verified-against`) |
| -------------- | ----------------------------------------- |
| 런타임 프로토콜과 컴파일 작업 공간 | `renderer/src/` |
| 바이너리 설치와 프로젝트 프로세스 수명 | `src/main/kotlin/com/livteam/typninja/runtime/` |
| 진단 트리거와 패키지 감지 | `src/main/kotlin/com/livteam/typninja/preview/TypstAutoCompileService.kt` |
| 편집기 결합과 JCEF SVG 미리보기 | `src/main/kotlin/com/livteam/typninja/preview/TypstSplitEditorProvider.kt`, `src/main/kotlin/com/livteam/typninja/preview/TypstPreviewFileEditor.kt` |
